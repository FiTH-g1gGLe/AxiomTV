package com.github.damontecres.wholphin.axiom.data

import com.github.damontecres.wholphin.axiom.AxiomLog
import com.github.damontecres.wholphin.axiom.xtream.XtreamClient
import com.github.damontecres.wholphin.axiom.xtream.XtreamLogin
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
    ) {
        val account: Flow<AccountEntity?> =
            dao.observeAccount().onEach { account ->
                AxiomLog.secrets = if (account != null) listOf(account.password, account.username) else emptyList()
            }

        fun categories(accountId: Long): Flow<List<CategoryEntity>> = dao.observeCategories(accountId)

        fun channels(
            accountId: Long,
            categoryId: String?,
        ): Flow<List<ChannelEntity>> =
            if (categoryId == null) {
                dao.observeAllChannels(accountId)
            } else {
                dao.observeChannels(accountId, categoryId)
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
            Timber.i("Signed in. status=%s expiry=%s", info.status, info.expiry)
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
            dao.updateAccount(account.copy(lastSync = System.currentTimeMillis()))
            Timber.i(
                "Synced %d categories, %d channels in %d ms",
                categoryEntities.size,
                channelEntities.size,
                System.currentTimeMillis() - started,
            )
        }

        fun streamUrl(
            account: AccountEntity,
            channel: ChannelEntity,
        ): String = xtream.liveUrl(account.toLogin(), channel.streamId)

        private fun AccountEntity.toLogin() = XtreamLogin(server, username, password)
    }
