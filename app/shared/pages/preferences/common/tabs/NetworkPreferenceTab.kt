package me.him188.ani.app.ui.preference.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.media.MediaCacheManager
import me.him188.ani.app.data.media.MediaSourceManager
import me.him188.ani.app.data.models.MediaSourceProxyPreferences
import me.him188.ani.app.data.models.ProxyPreferences
import me.him188.ani.app.data.repositories.PreferencesRepository
import me.him188.ani.app.tools.MonoTasker
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.launchInMain
import me.him188.ani.app.ui.foundation.rememberViewModel
import me.him188.ani.app.ui.preference.PreferenceScope
import me.him188.ani.app.ui.preference.PreferenceTab
import me.him188.ani.app.ui.preference.SwitchItem
import me.him188.ani.app.ui.subject.episode.mediaFetch.getMediaSourceIcon
import me.him188.ani.app.ui.subject.episode.mediaFetch.renderMediaSource
import me.him188.ani.app.ui.subject.episode.mediaFetch.renderMediaSourceDescription
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.subject.SubjectProvider
import me.him188.ani.datasources.bangumi.BangumiSubjectProvider
import me.him188.ani.utils.ktor.ClientProxyConfigValidator
import me.him188.ani.utils.logging.info
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Stable
class NetworkPreferenceViewModel : AbstractViewModel(), KoinComponent {
    private val preferencesRepository: PreferencesRepository by inject()
    private val mediaSourceManager: MediaSourceManager by inject()
    private val bangumiSubjectProvider by inject<SubjectProvider>()

    private val proxyPreferences = preferencesRepository.proxyPreferences
    private val updater = MonoTasker(backgroundScope)

    ///////////////////////////////////////////////////////////////////////////
    // Media Testing
    ///////////////////////////////////////////////////////////////////////////

    private val mediaTestScope = MonoTasker(backgroundScope)


    val mediaSourceTesters = mediaSourceManager.allIds
        .filterNot { it == MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID }
        .map { id -> createMediaSourceTester(id) }
        .sortedBy { it.id.lowercase() }

    private fun createMediaSourceTester(id: String): MediaSourceTester {
        val source = mediaSourceManager.enabledSources.map { sources ->
            sources.firstOrNull { it.mediaSourceId == id }
        }
        return MediaSourceTester(
            id = id,
        ) {
            when (source.first()?.checkConnection()) {
                ConnectionStatus.SUCCESS -> MediaTestResult.SUCCESS
                ConnectionStatus.FAILED -> MediaTestResult.FAILED
                null -> MediaTestResult.NOT_ENABLED
            }
        }.apply {
            launchInMain {
                source.collect {
                    this@apply.reset()
                }
            }
        }
    }

    val nonMediaSourceTesters = listOf(
        MediaSourceTester(
            id = BangumiSubjectProvider.ID, // Bangumi 顺便也测一下
        ) {
            if (bangumiSubjectProvider.testConnection() == ConnectionStatus.SUCCESS) {
                MediaTestResult.SUCCESS
            } else {
                MediaTestResult.FAILED
            }
        }
    )

    val allMediaTesters =
        mediaSourceTesters + nonMediaSourceTesters

    fun testSources() {
        mediaTestScope.launch {
            supervisorScope {
                allMediaTesters.forEach {
                    this@launch.launch {
                        it.test()
                    }
                }
            }
        }
    }

    fun cancelTest() {
        mediaTestScope.cancel()
    }

    ///////////////////////////////////////////////////////////////////////////
    // Proxy Preferences
    ///////////////////////////////////////////////////////////////////////////

    val proxyPreferencesFlow = proxyPreferences.flow.shareInBackground()
    fun updateProxyPreferences(proxyPreferences: ProxyPreferences) {
        updater.launch {
            logger.info { "Updating proxy preferences: $proxyPreferences" }
            preferencesRepository.proxyPreferences.set(proxyPreferences)
        }
    }

    /**
     * @param sourceId [MediaSource.mediaSourceId]
     */
    fun preferencePerSource(sourceId: String): Flow<MediaSourceProxyPreferences?> {
        return proxyPreferencesFlow.map { it.perSource[sourceId] }
    }
}

@Composable
fun NetworkPreferenceTab(
    vm: NetworkPreferenceViewModel = rememberViewModel { NetworkPreferenceViewModel() },
    modifier: Modifier = Modifier,
) {
    val proxyPreferences by vm.proxyPreferencesFlow.collectAsState(ProxyPreferences.Default)

    PreferenceTab(modifier) {
        Group(
            title = { Text("全局默认代理") },
            description = {
                Text("如果数据源没有单独配置代理，则使用此代理设置")
            }
        ) {
            SwitchItem(
                checked = proxyPreferences.default.enabled,
                onCheckedChange = {
                    vm.updateProxyPreferences(proxyPreferences.copy(default = proxyPreferences.default.copy(enabled = it)))
                },
                title = { Text("启用代理") },
                description = { Text("启用后下面的配置才生效") },
            )

            HorizontalDividerItem()

            var url by remember(proxyPreferences) {
                mutableStateOf(proxyPreferences.default.config.url)
            }
            TextFieldItem(
                url,
                { url = it.trim() },
                title = { Text("代理地址") },
                description = {
                    Text(
                        "示例: http://127.0.0.1:7890 或 socks5://127.0.0.1:1080"
                    )
                },
                onValueChangeCompleted = {
                    vm.updateProxyPreferences(
                        proxyPreferences.copy(
                            default = proxyPreferences.default.copy(
                                config = proxyPreferences.default.config.copy(
                                    url = url
                                )
                            )
                        )
                    )
                },
                isErrorProvider = {
                    !ClientProxyConfigValidator.isValidProxy(url)
                }
            )

            HorizontalDividerItem()

            var username by remember(proxyPreferences) {
                mutableStateOf(proxyPreferences.default.config.authorization?.username ?: "")
            }

            var password by remember(proxyPreferences) {
                mutableStateOf(proxyPreferences.default.config.authorization?.password ?: "")
            }

            TextFieldItem(
                username,
                { username = it },
                title = { Text("用户名") },
                description = { Text("可选") },
                placeholder = { Text("无") },
                onValueChangeCompleted = {
                    vm.updateProxyPreferences(
                        proxyPreferences.copy(
                            default = proxyPreferences.default.copy(
                                config = proxyPreferences.default.config.copy(
                                    authorization = proxyPreferences.default.config.authorization?.copy(
                                        username = username
                                    )
                                )
                            )
                        )
                    )
                }
            )

            HorizontalDividerItem()

            TextFieldItem(
                password,
                { password = it },
                title = { Text("密码") },
                description = { Text("可选") },
                placeholder = { Text("无") },
                onValueChangeCompleted = {
                    vm.updateProxyPreferences(
                        proxyPreferences.copy(
                            default = proxyPreferences.default.copy(
                                config = proxyPreferences.default.config.copy(
                                    authorization = proxyPreferences.default.config.authorization?.copy(
                                        password = password
                                    )
                                )
                            )
                        )
                    )
                }
            )
        }

        Group(
            title = { Text("数据源测试") },
            description = { Text("测试是否能正常连接。除 Bangumi 外，有任一数据源测试成功即可正常观看和下载") }
        ) {
            for (tester in vm.mediaSourceTesters) {
                MediaSourceTesterView(tester)
            }

            HorizontalDividerItem()

            for (tester in vm.nonMediaSourceTesters) {
                MediaSourceTesterView(tester)
            }

            TextButtonItem(
                onClick = {
                    if (vm.allMediaTesters.any { it.isTesting }) {
                        vm.cancelTest()
                    } else {
                        vm.testSources()
                    }
                },
                title = {
                    if (vm.allMediaTesters.any { it.isTesting }) {
                        Text("终止测试")
                    } else {
                        Text("开始测试")
                    }
                },
            )
        }
    }
}

@Composable
private fun PreferenceScope.MediaSourceTesterView(tester: MediaSourceTester) {
    TextItem(
        title = { Text(remember(tester.id) { renderMediaSource(tester.id) }) },
        icon = {
            val icon = getMediaSourceIcon(tester.id)
            Image(
                icon
                    ?: rememberVectorPainter(Icons.Rounded.DisplaySettings),
                null,
                Modifier.clip(MaterialTheme.shapes.extraSmall).size(48.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                colorFilter = if (icon == null) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
            )
        },
        description =
        if (tester.id == BangumiSubjectProvider.ID) {
            { Text("提供观看记录数据，无需代理") }
        } else {
            renderMediaSourceDescription(tester.id)?.let {
                { Text(it) }
            }
        },
        action = {
            if (tester.isTesting) {
                CircularProgressIndicator(
                    Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                when (tester.result) {
                    MediaTestResult.SUCCESS -> {
                        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }

                    MediaTestResult.FAILED -> {
                        Icon(Icons.Rounded.Cancel, null, tint = MaterialTheme.colorScheme.error)
                    }

                    MediaTestResult.NOT_ENABLED -> {
                        Text("未启用")
                    }

                    null -> {
                        Text("等待测试")
                    }
                }
            }
        }
    )
}

enum class MediaTestResult {
    SUCCESS,
    FAILED,
    NOT_ENABLED
}

@Stable
class MediaSourceTester(
    val id: String,
    private val testConnection: suspend () -> MediaTestResult,
) {
    var isTesting by mutableStateOf(false)
    var result: MediaTestResult? by mutableStateOf(null)

    fun reset() {
        isTesting = false
        result = null
    }

    suspend fun test() {
        withContext(Dispatchers.Main) {
            isTesting = true
        }
        try {
            val res = testConnection()
            withContext(Dispatchers.Main) {
                result = res
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
                result = MediaTestResult.FAILED
            }
            throw e
        } finally {
            // We can't use `withContext` be cause this scope has already been cancelled
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.Main) {
                isTesting = false
            }
        }
    }
}