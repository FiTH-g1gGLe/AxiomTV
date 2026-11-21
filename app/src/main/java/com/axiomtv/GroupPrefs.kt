package com.axiomtv

import android.content.Context
import com.axiomtv.data.CategoryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which live channel groups are hidden, and the order the rest are shown in. */
data class GroupPrefs(
    val hidden: Set<String> = emptySet(),
    /** Category ids in the order the user arranged them; groups not listed follow in provider order. */
    val order: List<String> = emptyList(),
    /** Group Live TV opens on (null = All channels) */
    val startGroup: String? = null,
    /** Hide a channel's SD version when an HD, FHD or 4K version of it is in the same list */
    val hideSd: Boolean = false,
) {
    fun arrange(categories: List<CategoryEntity>): List<CategoryEntity> {
        if (order.isEmpty()) return categories
        val position = order.withIndex().associate { it.value to it.index }
        return categories.withIndex().sortedBy { (index, category) ->
            position[category.categoryId]?.toLong() ?: (order.size.toLong() + index)
        }.map { it.value }
    }
}

@Singleton
class GroupPrefsStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("axiomtv_groups", Context.MODE_PRIVATE)
        private val state = MutableStateFlow(load())
        val prefsFlow: StateFlow<GroupPrefs> = state.asStateFlow()
        val current: GroupPrefs get() = state.value

        private fun load(): GroupPrefs =
            GroupPrefs(
                hidden = prefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet(),
                order = prefs.getString("order", "")?.split(SEP)?.filter { it.isNotEmpty() } ?: emptyList(),
                startGroup = prefs.getString("start", null),
                hideSd = prefs.getBoolean("hide_sd", false),
            )

        fun set(value: GroupPrefs) {
            state.value = value
            prefs
                .edit()
                .putStringSet("hidden", value.hidden)
                .putString("order", value.order.joinToString(SEP))
                .putString("start", value.startGroup)
                .putBoolean("hide_sd", value.hideSd)
                .apply()
        }

        fun toggleHidden(categoryId: String) {
            val hidden = current.hidden
            set(current.copy(hidden = if (categoryId in hidden) hidden - categoryId else hidden + categoryId))
        }

        /** Moves a group up (-1) or down (+1) within the full arranged list. */
        fun move(
            all: List<CategoryEntity>,
            categoryId: String,
            step: Int,
        ) {
            val ids = current.arrange(all).map { it.categoryId }.toMutableList()
            val from = ids.indexOf(categoryId)
            val to = from + step
            if (from < 0 || to !in ids.indices) return
            ids.removeAt(from)
            ids.add(to, categoryId)
            set(current.copy(order = ids))
        }

        fun setStartGroup(categoryId: String?) = set(current.copy(startGroup = categoryId))

        fun reset() = set(GroupPrefs(startGroup = current.startGroup))

        private companion object {
            const val SEP = "\u001F"
        }
    }

private val SD_TAG = Regex("""(?<![A-Za-z0-9])SD(?![A-Za-z0-9])""")
private val QUALITY_TAGS = Regex("""(?i)(?<![a-z0-9])(sd|hd|fhd|uhd|4k|hevc|h\.?265|raw)(?![a-z0-9])|[\[\]()|]""")

/** "BBC One SD" and "BBC One FHD" both become "bbc one", so versions of one channel can be matched. */
private fun channelBase(name: String) = QUALITY_TAGS.replace(name, " ").replace(Regex("\\s+"), " ").trim().lowercase()

/**
 * Leaves out SD channels that also come in better quality in the same list. A channel that only exists in SD stays,
 * so nothing disappears completely.
 */
fun hideSdDuplicates(channels: List<com.axiomtv.data.ChannelEntity>): List<com.axiomtv.data.ChannelEntity> {
    val betterVersions = channels.filter { !SD_TAG.containsMatchIn(it.name) }.mapTo(HashSet()) { channelBase(it.name) }
    return channels.filter { !SD_TAG.containsMatchIn(it.name) || channelBase(it.name) !in betterVersions }
}
