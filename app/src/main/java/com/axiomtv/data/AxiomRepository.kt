package com.axiomtv.data

import com.axiomtv.AxiomLog
import com.axiomtv.epg.EpgLoader
import com.axiomtv.xtream.XtreamClient
import com.axiomtv.xtream.XtreamLogin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AxiomRepository
    @Inject
    constructor(
        private val dao: AxiomDao,
        private val xtream: XtreamClient,
        private val epgLoader: EpgLoader,
    ) {
        val account: Flow<AccountEntity?> =
            dao.observeAccount().onEach { account ->
                AxiomLog.secrets = if (account != null) listOf(account.password, account.username) else emptyList()
            }

        fun categories(accountId: Long): Flow<List<CategoryEntity>> = dao.observeCategories(accountId)

        /** Channels the provider keeps catch-up for */
        fun archiveChannels(accountId: Long): Flow<List<ChannelEntity>> = dao.observeArchiveChannels(accountId)

        fun channels(
            accountId: Long,
            categoryId: String?,
        ): Flow<List<ChannelEntity>> =
            when (categoryId) {
                null -> dao.observeAllChannels(accountId)
                FAVOURITES_CATEGORY -> dao.observeFavouriteChannels(accountId)
                else -> dao.observeChannels(accountId, categoryId)
            }

        /** Checks the login with the server, saves it, then downloads the channel list. */
        suspend fun signIn(
            server: String,
            username: String,
            password: String,
        ) {
            val login = XtreamLogin(xtream.normaliseServer(server), username.trim(), password.trim())
            AxiomLog.secrets = listOf(login.password, login.username)
            val info = xtream.authenticate(login)
            Timber.i("Signed in. status=%s expiry=%s timezone=%s", info.status, info.expiry, info.timezone)
            info.timezone?.let { serverZoneId = it }
            dao.clearEverything()
            val host = login.server.substringAfter("://").substringBefore("/").substringBefore(":")
            dao.insertAccount(
                AccountEntity(
                    name = host,
                    server = login.server,
                    username = login.username,
                    password = login.password,
                    expiry = info.expiry,
                ),
            )
            dao.getAccount()?.let { syncLive(it) }
        }

        /** Server timezone id, remembered from the last login. */
        private var serverZoneId: String? = null

        private suspend fun serverZone(account: AccountEntity): java.time.ZoneId {
            val id =
                serverZoneId ?: try {
                    xtream.authenticate(account.toLogin()).timezone.also { serverZoneId = it }
                } catch (ex: Exception) {
                    Timber.w(ex, "Couldn't get server timezone")
                    null
                }
            return try {
                if (id != null) java.time.ZoneId.of(id) else java.time.ZoneId.systemDefault()
            } catch (ex: Exception) {
                java.time.ZoneId.systemDefault()
            }
        }

        /** Catch-up link for a past programme on an archive channel. */
        suspend fun catchupUrl(
            account: AccountEntity,
            channel: ChannelEntity,
            programme: ProgrammeEntity,
        ): String {
            val minutes = ((programme.stop - programme.start) / 60_000L).toInt() + 2
            return xtream.catchupUrl(account.toLogin(), channel.streamId, programme.start, minutes, serverZone(account))
        }

        suspend fun refresh() {
            dao.getAccount()?.let { syncLive(it) }
        }

        suspend fun signOut() {
            dao.clearEverything()
        }

        private suspend fun syncLive(account: AccountEntity) {
            val login = account.toLogin()
            val started = System.currentTimeMillis()
            val categories = xtream.liveCategories(login)
            val streams = xtream.liveStreams(login)
            val categoryEntities =
                categories.mapIndexed { index, category ->
                    CategoryEntity(account.id, category.id, category.name, index)
                }
            val channelEntities =
                streams.mapIndexed { index, stream ->
                    ChannelEntity(
                        accountId = account.id,
                        streamId = stream.streamId,
                        number = stream.number,
                        name = stream.name,
                        logo = stream.icon,
                        categoryId = stream.categoryId,
                        epgChannelId = stream.epgChannelId,
                        hasArchive = stream.hasArchive,
                        sortOrder = index,
                    )
                }
            dao.replaceLive(account.id, categoryEntities, channelEntities)
            dao.getAccount()?.let { dao.updateAccount(it.copy(lastSync = System.currentTimeMillis())) }
            Timber.i(
                "Synced %d categories, %d channels in %d ms",
                categoryEntities.size,
                channelEntities.size,
                System.currentTimeMillis() - started,
            )
        }

        /** Guide entries for one channel between two times. */
        suspend fun programmes(
            accountId: Long,
            epgChannelId: String,
            from: Long,
            to: Long,
        ): List<ProgrammeEntity> = dao.programmes(accountId, epgChannelId.lowercase(), from, to)

        suspend fun setGuideUrl(url: String?) {
            val account = dao.getAccount() ?: return
            dao.updateAccount(account.copy(epgUrl = url?.trim()?.takeIf { it.isNotEmpty() }))
        }

        fun guideNeedsRefresh(account: AccountEntity): Boolean =
            System.currentTimeMillis() - account.lastEpgSync > GUIDE_MAX_AGE_MS

        /**
         * Downloads the guide (provider's, or the custom link) and replaces the stored one.
         * Keeps programmes from 3 hours ago to 30 hours ahead.
         * @return number of programmes stored
         */
        suspend fun syncGuide(onProgress: suspend (Int) -> Unit = {}): Int {
            val account = dao.getAccount() ?: return 0
            val url = account.epgUrl ?: xtream.epgUrl(account.toLogin())
            val wanted = dao.epgChannelIds(account.id).map { it.lowercase() }.toHashSet()
            if (wanted.isEmpty()) {
                Timber.w("No channels have guide ids, skipping guide")
                return 0
            }
            val now = System.currentTimeMillis()
            val started = now
            Timber.i("Loading guide (%s) for %d channels", if (account.epgUrl != null) "custom link" else "provider", wanted.size)
            val archive = dao.archiveEpgChannelIds(account.id).map { it.lowercase() }.toHashSet()
            dao.deleteProgrammes(account.id)
            var stored = 0
            val count =
                epgLoader.load(
                    url,
                    wanted,
                    now - 3 * HOUR_MS,
                    now + 30 * HOUR_MS,
                    archiveChannels = archive,
                    archiveFrom = now - ARCHIVE_MS,
                ) { batch ->
                    dao.insertProgrammes(
                        batch.map {
                            ProgrammeEntity(
                                accountId = account.id,
                                epgChannelId = it.channelId,
                                start = it.start,
                                stop = it.stop,
                                title = it.title,
                                description = it.description,
                            )
                        },
                    )
                    stored += batch.size
                    onProgress(stored)
                }
            dao.getAccount()?.let { dao.updateAccount(it.copy(lastEpgSync = System.currentTimeMillis())) }
            Timber.i("Guide stored: %d programmes in %d ms", count, System.currentTimeMillis() - started)
            return count
        }

        fun streamUrl(
            account: AccountEntity,
            channel: ChannelEntity,
        ): String = xtream.liveUrl(account.toLogin(), channel.streamId)

        private fun AccountEntity.toLogin() = XtreamLogin(server, username, password)

        companion object {
            /** Pseudo category id for favourite channels */
            const val FAVOURITES_CATEGORY = "__favourites__"

            /** How far back catch-up goes (guide is kept this long for archive channels) */
            const val ARCHIVE_MS = 3L * 24 * 60 * 60 * 1000
            private const val HOUR_MS = 60L * 60 * 1000
            private const val GUIDE_MAX_AGE_MS = 12 * HOUR_MS
        }
    }
