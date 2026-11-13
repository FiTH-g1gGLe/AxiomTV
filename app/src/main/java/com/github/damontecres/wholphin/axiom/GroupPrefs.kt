package com.github.damontecres.wholphin.axiom

import android.content.Context
import com.github.damontecres.wholphin.axiom.data.CategoryEntity
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
            )

        fun set(value: GroupPrefs) {
            state.value = value
            prefs
                .edit()
                .putStringSet("hidden", value.hidden)
                .putString("order", value.order.joinToString(SEP))
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

        fun reset() = set(GroupPrefs())

        private companion object {
            const val SEP = "\u001F"
        }
    }
