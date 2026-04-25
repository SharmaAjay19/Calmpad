package com.calmpad.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calmpad.data.*
import com.calmpad.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class CalmPadState(
    val notes: List<NoteEntity> = emptyList(),
    val sections: List<SectionEntity> = emptyList(),
    val activeNoteId: String? = null,
    val activeSectionId: String = "default",
    val searchQuery: String = "",
    val theme: AppTheme = AppTheme.LIGHT,
    val fontFamily: String = "sans",
    val showSidebar: Boolean = false,
    val showToolsPanel: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
)

enum class SaveStatus { IDLE, SAVING, SAVED }

@HiltViewModel
class CalmPadViewModel @Inject constructor(
    private val noteDao: NoteDao,
    private val sectionDao: SectionDao,
    private val prefs: PreferencesRepository,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val converters = Converters()

    private val _state = MutableStateFlow(CalmPadState())
    val state: StateFlow<CalmPadState> = _state.asStateFlow()

    private var saveJob: Job? = null

    init {
        loadPreferences()
        loadSections()
        loadNotes()
    }

    // ── Data Loading ──

    private fun loadPreferences() {
        viewModelScope.launch {
            combine(prefs.theme, prefs.font, prefs.activeNoteId, prefs.activeSectionId) { theme, font, noteId, secId ->
                Triple(theme to font, noteId, secId)
            }.collect { (themeFont, noteId, secId) ->
                val (theme, font) = themeFont
                _state.update {
                    it.copy(
                        theme = AppTheme.entries.find { t -> t.name.lowercase() == theme } ?: AppTheme.LIGHT,
                        fontFamily = font,
                        activeNoteId = noteId ?: it.activeNoteId,
                        activeSectionId = secId
                    )
                }
            }
        }
    }

    private fun loadSections() {
        viewModelScope.launch {
            sectionDao.getAllSections().collect { sections ->
                if (sections.isEmpty()) {
                    // Create default section
                    val defaultSection = SectionEntity(
                        id = "default",
                        name = "Quick Notes",
                        noteOrder = "[]",
                        sortIndex = 0
                    )
                    sectionDao.upsertSection(defaultSection)
                } else {
                    _state.update { it.copy(sections = sections) }
                }
            }
        }
    }

    private fun loadNotes() {
        viewModelScope.launch {
            // Use a combine to react to section changes
            _state.map { it.activeSectionId }.distinctUntilChanged().collectLatest { sectionId ->
                if (_state.value.searchQuery.isBlank()) {
                    noteDao.getNotesBySection(sectionId).collect { notes ->
                        _state.update { it.copy(notes = notes) }
                        // Create welcome note if empty and default section
                        if (notes.isEmpty() && sectionId == "default" && _state.value.sections.isNotEmpty()) {
                            val existing = noteDao.getAllNotes()
                            if (existing.isEmpty()) {
                                createWelcomeNote()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun createWelcomeNote() {
        val id = generateId()
        val note = NoteEntity(
            id = id,
            sectionId = "default",
            title = "Getting Started",
            content = "Welcome to CalmPad!\n\nThis is your personal note-taking app.\n\n• Create sections to organize your notes\n• Drag and drop to reorder\n• Use the toolbar for formatting\n• Export backups anytime",
            updatedAt = System.currentTimeMillis(),
            versions = "[]"
        )
        noteDao.upsertNote(note)
        updateSectionOrder("default") { it + id }
        _state.update { it.copy(activeNoteId = id) }
        prefs.setActiveNoteId(id)
    }

    // ── Note Operations ──

    fun createNote() {
        viewModelScope.launch {
            val id = generateId()
            val sectionId = _state.value.activeSectionId
            val note = NoteEntity(
                id = id,
                sectionId = sectionId,
                title = "",
                content = "",
                updatedAt = System.currentTimeMillis(),
                versions = "[]"
            )
            noteDao.upsertNote(note)
            updateSectionOrder(sectionId) { listOf(id) + it }
            _state.update { it.copy(activeNoteId = id, showSidebar = false) }
            prefs.setActiveNoteId(id)
        }
    }

    fun selectNote(noteId: String) {
        viewModelScope.launch {
            _state.update { it.copy(activeNoteId = noteId, showSidebar = false) }
            prefs.setActiveNoteId(noteId)
        }
    }

    fun updateNoteTitle(title: String) {
        val noteId = _state.value.activeNoteId ?: return
        _state.update { state ->
            state.copy(notes = state.notes.map {
                if (it.id == noteId) it.copy(title = title, updatedAt = System.currentTimeMillis())
                else it
            })
        }
        scheduleSave(noteId)
    }

    fun updateNoteContent(content: String) {
        val noteId = _state.value.activeNoteId ?: return
        _state.update { state ->
            state.copy(
                notes = state.notes.map {
                    if (it.id == noteId) {
                        val versions = converters.parseVersions(it.versions).toMutableList()
                        val now = System.currentTimeMillis()
                        val lastTime = versions.lastOrNull()?.time ?: 0L
                        if (now - lastTime > 600_000 && it.content != content) {
                            versions.add(NoteVersion(content = it.content, time = now))
                            if (versions.size > 5) versions.removeAt(0)
                        }
                        it.copy(
                            content = content,
                            updatedAt = now,
                            versions = converters.versionsToString(versions)
                        )
                    } else it
                },
                saveStatus = SaveStatus.SAVING
            )
        }
        scheduleSave(noteId)
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            noteDao.deleteNote(noteId)
            val sectionId = _state.value.notes.find { it.id == noteId }?.sectionId ?: _state.value.activeSectionId
            updateSectionOrder(sectionId) { it.filter { id -> id != noteId } }
            if (_state.value.activeNoteId == noteId) {
                _state.update { it.copy(activeNoteId = null) }
                prefs.setActiveNoteId(null)
            }
            // Refresh notes
            val notes = if (_state.value.searchQuery.isBlank()) {
                _state.value.notes.filter { it.id != noteId }
            } else _state.value.notes.filter { it.id != noteId }
            _state.update { it.copy(notes = notes) }
        }
    }

    // ── Section Operations ──

    fun createSection(name: String) {
        viewModelScope.launch {
            val id = generateId()
            val section = SectionEntity(
                id = id,
                name = name,
                noteOrder = "[]",
                sortIndex = _state.value.sections.size
            )
            sectionDao.upsertSection(section)
            _state.update { it.copy(activeSectionId = id) }
            prefs.setActiveSectionId(id)
        }
    }

    fun switchSection(sectionId: String) {
        viewModelScope.launch {
            _state.update { it.copy(activeSectionId = sectionId, searchQuery = "", activeNoteId = null) }
            prefs.setActiveSectionId(sectionId)
            prefs.setActiveNoteId(null)
            noteDao.getNotesBySection(sectionId).first().let { notes ->
                _state.update { it.copy(notes = notes) }
            }
        }
    }

    fun deleteSection(sectionId: String) {
        viewModelScope.launch {
            if (sectionDao.getSectionCount() <= 1) return@launch
            sectionDao.deleteSection(sectionId)
            noteDao.deleteNotesBySection(sectionId)
            val remaining = sectionDao.getAllSectionsList()
            if (remaining.isNotEmpty()) {
                val newActiveId = remaining.first().id
                _state.update { it.copy(activeSectionId = newActiveId, activeNoteId = null) }
                prefs.setActiveSectionId(newActiveId)
                prefs.setActiveNoteId(null)
            }
        }
    }

    // ── Search ──

    fun updateSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            viewModelScope.launch {
                noteDao.getNotesBySection(_state.value.activeSectionId).first().let { notes ->
                    _state.update { it.copy(notes = notes) }
                }
            }
        } else {
            viewModelScope.launch {
                noteDao.searchNotes(query).first().let { notes ->
                    _state.update { it.copy(notes = notes) }
                }
            }
        }
    }

    // ── Reorder ──

    fun moveNote(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val sectionId = _state.value.activeSectionId
            val section = _state.value.sections.find { it.id == sectionId } ?: return@launch
            val order = converters.parseNoteOrder(section.noteOrder).toMutableList()

            // Ensure all note IDs are in the order list
            val sectionNoteIds = _state.value.notes.map { it.id }
            sectionNoteIds.forEach { id -> if (id !in order) order.add(id) }
            val cleanOrder = order.filter { it in sectionNoteIds }.toMutableList()

            if (fromIndex < 0 || toIndex < 0 || fromIndex >= cleanOrder.size || toIndex >= cleanOrder.size) return@launch

            val item = cleanOrder.removeAt(fromIndex)
            cleanOrder.add(toIndex, item)

            val updated = section.copy(noteOrder = converters.noteOrderToString(cleanOrder))
            sectionDao.upsertSection(updated)
            _state.update { state ->
                state.copy(sections = state.sections.map { if (it.id == sectionId) updated else it })
            }
            // Reorder local notes list
            reorderLocalNotes()
        }
    }

    private fun reorderLocalNotes() {
        val section = _state.value.sections.find { it.id == _state.value.activeSectionId } ?: return
        val order = converters.parseNoteOrder(section.noteOrder)
        if (order.isEmpty()) return
        val orderMap = order.withIndex().associate { (i, id) -> id to i }
        val sorted = _state.value.notes.sortedWith(compareBy { orderMap[it.id] ?: Int.MAX_VALUE })
        _state.update { it.copy(notes = sorted) }
    }

    // ── Preferences ──

    fun cycleTheme() {
        viewModelScope.launch {
            val themes = AppTheme.entries
            val current = _state.value.theme
            val next = themes[(themes.indexOf(current) + 1) % themes.size]
            _state.update { it.copy(theme = next) }
            prefs.setTheme(next.name.lowercase())
        }
    }

    fun setFont(font: String) {
        viewModelScope.launch {
            _state.update { it.copy(fontFamily = font) }
            prefs.setFont(font)
        }
    }

    fun toggleSidebar() {
        _state.update { it.copy(showSidebar = !it.showSidebar) }
    }

    fun setSidebarVisible(visible: Boolean) {
        _state.update { it.copy(showSidebar = visible) }
    }

    fun toggleToolsPanel() {
        _state.update { it.copy(showToolsPanel = !it.showToolsPanel) }
    }

    fun dismissToolsPanel() {
        _state.update { it.copy(showToolsPanel = false) }
    }

    // ── Backup / Import ──

    fun exportBackupJson(): String {
        val notes = _state.value.notes
        val sections = _state.value.sections
        val backup = BackupData(
            notes = notes.map { BackupNote(it.id, it.sectionId, it.title, it.content, it.updatedAt, it.versions) },
            sections = sections.map { BackupSection(it.id, it.name, it.noteOrder, it.sortIndex) },
            preferences = BackupPreferences(_state.value.theme.name.lowercase(), _state.value.fontFamily)
        )
        return json.encodeToString(backup)
    }

    fun importBackup(jsonString: String) {
        viewModelScope.launch {
            try {
                val backup = json.decodeFromString<BackupData>(jsonString)
                backup.sections.forEach { sec ->
                    sectionDao.upsertSection(SectionEntity(sec.id, sec.name, sec.noteOrder, sec.sortIndex))
                }
                backup.notes.forEach { note ->
                    noteDao.upsertNote(NoteEntity(note.id, note.sectionId, note.title, note.content, note.updatedAt, note.versions))
                }
                // Reload
                val sections = sectionDao.getAllSectionsList()
                val notes = noteDao.getAllNotes().filter { it.sectionId == _state.value.activeSectionId }
                _state.update { it.copy(sections = sections, notes = notes) }
            } catch (_: Exception) { }
        }
    }

    // ── Print Content ──

    fun getPrintContentForNote(): Pair<String, String>? {
        val note = _state.value.notes.find { it.id == _state.value.activeNoteId } ?: return null
        return note.title.ifBlank { "Untitled" } to note.content
    }

    fun getPrintContentForNotebook(): List<Pair<String, String>> {
        return getOrderedNotes().map { (it.title.ifBlank { "Untitled" }) to it.content }
    }

    fun getActiveSection(): SectionEntity? =
        _state.value.sections.find { it.id == _state.value.activeSectionId }

    // ── Helpers ──

    fun getOrderedNotes(): List<NoteEntity> {
        val section = _state.value.sections.find { it.id == _state.value.activeSectionId }
        val notes = _state.value.notes.filter { it.sectionId == _state.value.activeSectionId }
        if (section == null) return notes.sortedByDescending { it.updatedAt }

        val order = converters.parseNoteOrder(section.noteOrder)
        if (order.isEmpty()) return notes.sortedByDescending { it.updatedAt }

        val orderMap = order.withIndex().associate { (i, id) -> id to i }
        return notes.sortedWith(compareBy { orderMap[it.id] ?: Int.MAX_VALUE })
    }

    private fun scheduleSave(noteId: String) {
        saveJob?.cancel()
        _state.update { it.copy(saveStatus = SaveStatus.SAVING) }
        saveJob = viewModelScope.launch {
            delay(1000)
            val note = _state.value.notes.find { it.id == noteId }
            if (note != null) {
                noteDao.upsertNote(note)
                _state.update { it.copy(saveStatus = SaveStatus.SAVED) }
                delay(2000)
                _state.update { it.copy(saveStatus = SaveStatus.IDLE) }
            }
        }
    }

    private suspend fun updateSectionOrder(sectionId: String, transform: (List<String>) -> List<String>) {
        val section = _state.value.sections.find { it.id == sectionId }
            ?: sectionDao.getAllSectionsList().find { it.id == sectionId }
            ?: return
        val order = converters.parseNoteOrder(section.noteOrder)
        val newOrder = transform(order)
        val updated = section.copy(noteOrder = converters.noteOrderToString(newOrder))
        sectionDao.upsertSection(updated)
        _state.update { state ->
            state.copy(sections = state.sections.map { if (it.id == sectionId) updated else it })
        }
    }

    private fun generateId(): String = "_" + UUID.randomUUID().toString().take(9)
}

// ── Backup Data Classes ──

@kotlinx.serialization.Serializable
data class BackupData(
    val notes: List<BackupNote>,
    val sections: List<BackupSection>,
    val preferences: BackupPreferences = BackupPreferences()
)

@kotlinx.serialization.Serializable
data class BackupNote(
    val id: String,
    val sectionId: String,
    val title: String,
    val content: String,
    val updatedAt: Long,
    val versions: String = "[]"
)

@kotlinx.serialization.Serializable
data class BackupSection(
    val id: String,
    val name: String,
    val noteOrder: String = "[]",
    val sortIndex: Int = 0
)

@kotlinx.serialization.Serializable
data class BackupPreferences(
    val theme: String = "light",
    val font: String = "sans"
)
