package com.axiomtv.library

import com.axiomtv.data.AxiomDao
import com.axiomtv.data.FavouriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Favourite channels, films and series. kind = "channel", "movie" or "series". */
@Singleton
class FavouritesStore
    @Inject
    constructor(
        private val dao: AxiomDao,
    ) {
        fun ids(
            accountId: Long,
            kind: String,
        ): Flow<Set<Long>> = dao.observeFavouriteIds(accountId, kind).map { it.toSet() }

        /** Adds or removes a favourite. Returns true if it is now a favourite. */
        suspend fun toggle(
            accountId: Long,
            kind: String,
            refId: Long,
        ): Boolean {
            val key = "$kind:$accountId:$refId"
            return if (dao.isFavourite(key) > 0) {
                dao.removeFavourite(key)
                false
            } else {
                dao.addFavourite(FavouriteEntity(key, accountId, kind, refId, System.currentTimeMillis()))
                true
            }
        }
    }
