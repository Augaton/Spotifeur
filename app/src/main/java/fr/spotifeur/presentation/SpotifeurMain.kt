package fr.spotifeur.presentation

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.spotifeur.BottomNavItem
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.preferences.NavBarStyle
import fr.spotifeur.data.preferences.sanitizeNavBarCornerRadius
import fr.spotifeur.presentation.components.DismissUndoBar
import fr.spotifeur.presentation.components.MiniPlayerBottomSpacer
import fr.spotifeur.presentation.components.MiniPlayerHeight
import fr.spotifeur.presentation.components.PlayerInternalNavigationBar
import fr.spotifeur.presentation.components.UnifiedPlayerSheetV2
import fr.spotifeur.presentation.components.calculatePlayerSheetCollapsedTargetY
import fr.spotifeur.presentation.components.resolveNavBarOccupiedHeight
import fr.spotifeur.presentation.components.resolveNavBarSurfaceHeight
import fr.spotifeur.presentation.components.sanitizeNavigationBarBottomInset
import fr.spotifeur.presentation.navigation.AppNavigation
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.screens.AccountScreen
import fr.spotifeur.presentation.utils.AppHapticsConfig
import fr.spotifeur.presentation.utils.LocalAppHapticsConfig
import fr.spotifeur.presentation.utils.NoOpHapticFeedback
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import kotlin.math.roundToInt

private data class DismissUndoBarSlice(val isVisible: Boolean = false, val durationMillis: Long = 4000L)

/** PixelPlayer's main scaffold: floating bar, blurred content, unified player sheet. */
@Composable
fun SpotifeurMain(model: SpotifeurModel) {
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    if (!discovery.spotifyEnabled) {
        if (account.restoring) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        } else {
            AccountScreen(model, account)
        }
        return
    }
    val playerViewModel = model.ui
    val settings by playerViewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by model.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.consumeMessage() }
    }

    val commonNavItems = remember {
        persistentListOf(
            BottomNavItem("Home", R.string.nav_bar_home, R.drawable.rounded_home_24, R.drawable.home_24_rounded_filled, Screen.Home),
            BottomNavItem("Search", R.string.nav_bar_search, R.drawable.rounded_search_24, R.drawable.rounded_search_24, Screen.Search),
            BottomNavItem("Library", R.string.nav_bar_library, R.drawable.rounded_library_music_24, R.drawable.round_library_music_24, Screen.Library),
        )
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var isSearchBarActive by remember { mutableStateOf(false) }
    val routesWithHiddenNavigationBar = remember {
        setOf(Screen.Settings.route, Screen.Equalizer.route, Screen.SettingsCategory.route, Screen.PlaylistDetail.route, Screen.AlbumDetail.route, Screen.ArtistDetail.route)
    }
    val shouldHideNavigationBar by remember(currentRoute, isSearchBarActive) {
        derivedStateOf {
            (currentRoute == Screen.Search.route && isSearchBarActive) || currentRoute in routesWithHiddenNavigationBar
        }
    }

    val playerConfig by playerViewModel.playerConfigSlice.collectAsStateWithLifecycle()
    val navBarStyle = playerConfig.navBarStyle
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val navBarCornerRadius = sanitizeNavBarCornerRadius(playerConfig.navBarCornerRadius)
    val useSmoothCorners = playerConfig.useSmoothCorners
    val isMiniPlayerDismissing by playerViewModel.isMiniPlayerDismissing.collectAsStateWithLifecycle()
    val disableBlurAllOver by playerViewModel.disableBlurAllOver.collectAsStateWithLifecycle()
    val predictiveBackCollapseFraction by playerViewModel.predictiveBackCollapseFraction.collectAsStateWithLifecycle()

    val systemNavBarInset = sanitizeNavigationBarBottomInset(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    )
    val horizontalPadding = if (navBarStyle == NavBarStyle.DEFAULT) {
        if (systemNavBarInset > 30.dp) 14.dp else systemNavBarInset
    } else {
        0.dp
    }
    val animatedBottomBarPadding by animateDpAsState(
        targetValue = if (navBarStyle == NavBarStyle.FULL_WIDTH) 0.dp else systemNavBarInset,
        animationSpec = tween(400),
        label = "BottomBarPadding"
    )
    val bottomBarPadding = animatedBottomBarPadding
    val navBarHeight = resolveNavBarSurfaceHeight(navBarStyle, systemNavBarInset, navBarCompactMode)
    val navBarOccupiedHeight by remember(systemNavBarInset, navBarCompactMode) {
        derivedStateOf { resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode) }
    }
    val navBarVisibilityProgressState = animateFloatAsState(
        targetValue = if (shouldHideNavigationBar) 0f else 1f,
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        label = "NavBarVisibilityProgress"
    )
    val navBarVisibilityProgress by navBarVisibilityProgressState
    val visibleNavBarOccupiedHeight by remember(navBarOccupiedHeight, navBarVisibilityProgress) {
        derivedStateOf { navBarOccupiedHeight * navBarVisibilityProgress }
    }
    val miniPlayerBottomMargin by remember(systemNavBarInset, visibleNavBarOccupiedHeight) {
        derivedStateOf {
            if (visibleNavBarOccupiedHeight > systemNavBarInset) visibleNavBarOccupiedHeight else systemNavBarInset
        }
    }
    val shouldRenderNavigationBar by remember(shouldHideNavigationBar, navBarVisibilityProgress) {
        derivedStateOf { !shouldHideNavigationBar || navBarVisibilityProgress > 0.01f }
    }
    val isNavBarEffectivelyHidden by remember(shouldHideNavigationBar, navBarVisibilityProgress) {
        derivedStateOf { shouldHideNavigationBar && navBarVisibilityProgress <= 0.01f }
    }

    val hapticsEnabled = settings.hapticsEnabled
    val platformHaptics = LocalHapticFeedback.current
    val rootView = LocalView.current
    LaunchedEffect(hapticsEnabled, rootView) { rootView.rootView?.isHapticFeedbackEnabled = hapticsEnabled }
    val scopedHaptics = remember(platformHaptics, hapticsEnabled) { if (hapticsEnabled) platformHaptics else NoOpHapticFeedback }
    CompositionLocalProvider(
        LocalAppHapticsConfig provides remember(hapticsEnabled) { AppHapticsConfig(enabled = hapticsEnabled) },
        LocalHapticFeedback provides scopedHaptics,
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (shouldRenderNavigationBar) {
                val currentSongId by remember {
                    playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
                }.collectAsStateWithLifecycle(initialValue = null)
                val showPlayerContentArea = currentSongId != null
                val navBarElevation = 3.dp
                val animatedNavBarCornerRadius = animateDpAsState(
                    targetValue = navBarCornerRadius.dp,
                    animationSpec = tween(400),
                    label = "NavBarCornerRadius"
                )
                val animatedDefaultTopCornerRadius = animateDpAsState(
                    targetValue = if (showPlayerContentArea && !isMiniPlayerDismissing) 10.dp else navBarCornerRadius.dp,
                    animationSpec = tween(400),
                    label = "NavBarDefaultTopCornerRadius"
                )
                var componentHeightPx by remember { mutableStateOf(0) }
                val density = LocalDensity.current
                val shadowOverflowPx = remember(navBarElevation, density) { with(density) { (navBarElevation * 8).toPx() } }
                val bottomBarPaddingPx = remember(bottomBarPadding, density) { with(density) { bottomBarPadding.toPx() } }
                val navBarElevationPx = remember(navBarElevation, density) { with(density) { navBarElevation.toPx() } }
                val navBarShapeCache = remember { NavBarShapeCache() }

                Box(
                    modifier = Modifier.fillMaxWidth().height(navBarOccupiedHeight).clipToBounds()
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = bottomBarPadding)
                            .onSizeChanged { componentHeightPx = it.height }
                            .graphicsLayer {
                                val expansionHide = if (showPlayerContentArea) {
                                    playerViewModel.playerContentExpansionFraction.value.coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                val routeHide = (1f - navBarVisibilityProgressState.value).coerceIn(0f, 1f)
                                val hideFraction = maxOf(expansionHide, routeHide)
                                translationY = (componentHeightPx + shadowOverflowPx + bottomBarPaddingPx) * hideFraction
                                alpha = 1f
                            }
                            .height(navBarHeight)
                            .padding(horizontal = horizontalPadding)
                            .graphicsLayer {
                                val fraction = playerViewModel.playerContentExpansionFraction.value
                                val safeFraction = fraction.coerceIn(0f, 1f)
                                val topDp = when {
                                    navBarStyle == NavBarStyle.DEFAULT -> animatedDefaultTopCornerRadius.value
                                    navBarStyle == NavBarStyle.FULL_WIDTH -> lerp(navBarCornerRadius.dp, 26.dp, safeFraction)
                                    showPlayerContentArea -> if (fraction < 0.2f) {
                                        lerp(navBarCornerRadius.dp, 26.dp, (fraction / 0.2f).coerceIn(0f, 1f))
                                    } else {
                                        26.dp
                                    }
                                    else -> navBarCornerRadius.dp
                                }
                                val bottomDp = when (navBarStyle) {
                                    NavBarStyle.FULL_WIDTH -> 0.dp
                                    else -> animatedNavBarCornerRadius.value
                                }
                                shape = navBarShapeCache.get(this, topDp.toPx(), bottomDp.toPx(), useSmoothCorners)
                                clip = true
                                shadowElevation = navBarElevationPx
                            },
                        color = NavigationBarDefaults.containerColor
                    ) {
                        PlayerInternalNavigationBar(
                            navController = navController,
                            navItems = commonNavItems,
                            currentRoute = currentRoute,
                            navBarStyle = navBarStyle,
                            compactMode = navBarCompactMode,
                            bottomBarPadding = bottomBarPadding,
                            onSearchIconDoubleTap = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val containerHeight = this.maxHeight
            val screenHeightPx = remember(containerHeight, density) { with(density) { containerHeight.toPx() } }
            val showPlayerContentInitially by remember {
                playerViewModel.stablePlayerState.map { it.currentSong?.id != null }.distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = false)
            val miniPlayerH = with(density) { MiniPlayerHeight.toPx() }
            val totalSheetHeightWhenContentCollapsedPx = if (showPlayerContentInitially) miniPlayerH else 0f
            val bottomMargin = miniPlayerBottomMargin
            val spacerPx = with(density) { MiniPlayerBottomSpacer.toPx() }
            val bottomMarginPx = with(density) { bottomMargin.toPx() }
            val sheetCollapsedTargetY = calculatePlayerSheetCollapsedTargetY(
                containerHeightPx = screenHeightPx,
                collapsedContentHeightPx = totalSheetHeightWhenContentCollapsedPx,
                bottomMarginPx = bottomMarginPx,
                bottomSpacerPx = spacerPx
            )
            val expansionFractionProvider = remember(playerViewModel.playerContentExpansionFraction) {
                { playerViewModel.playerContentExpansionFraction.value }
            }
            val blurEffectCache = remember { BlurEffectCache() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (disableBlurAllOver) {
                                renderEffect = null
                            } else {
                                val expansion = expansionFractionProvider()
                                val fraction = (expansion * (1f - predictiveBackCollapseFraction)).coerceIn(0f, 1f)
                                val quantizedBlurPx = (fraction * 120f / 2f).roundToInt() * 2f
                                renderEffect = blurEffectCache.get(quantizedBlurPx)
                            }
                        }
                    }
            ) {
                AppNavigation(
                    model = model,
                    navController = navController,
                    paddingValues = innerPadding,
                    onSearchBarActiveChange = { isSearchBarActive = it },
                )
            }

            val isExpandedOrExpanding by remember {
                derivedStateOf { playerViewModel.playerContentExpansionFraction.value > 0.01f }
            }
            AnimatedVisibility(
                visible = isExpandedOrExpanding,
                enter = fadeIn(animationSpec = tween(durationMillis = 350)),
                exit = fadeOut(animationSpec = tween(durationMillis = 350)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest.copy(
                                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.6f
                            )
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { playerViewModel.collapsePlayerSheet() }
                        }
                )
            }

            UnifiedPlayerSheetV2(
                playerViewModel = playerViewModel,
                sheetCollapsedTargetY = sheetCollapsedTargetY,
                collapsedStateHorizontalPadding = horizontalPadding,
                hideMiniPlayer = false,
                containerHeight = containerHeight,
                navController = navController,
                isNavBarHidden = isNavBarEffectivelyHidden
            )

            val dismissUndoBarSlice by remember {
                playerViewModel.playerUiState
                    .map { DismissUndoBarSlice(it.showDismissUndoBar, it.undoBarVisibleDuration) }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = DismissUndoBarSlice())
            AnimatedVisibility(
                visible = dismissUndoBarSlice.isVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding() + MiniPlayerBottomSpacer)
                    .padding(horizontal = horizontalPadding)
            ) {
                DismissUndoBar(
                    modifier = Modifier.fillMaxWidth().height(MiniPlayerHeight).padding(horizontal = 14.dp),
                    onUndo = playerViewModel::undoDismissPlaylist,
                    onClose = playerViewModel::hideDismissUndoBar,
                    durationMillis = dismissUndoBarSlice.durationMillis
                )
            }
        }
    }
    }
}

private class BlurEffectCache {
    private var lastRadiusPx: Float = Float.NaN
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    fun get(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (radiusPx <= 0f) {
            lastRadiusPx = 0f
            cached = null
            return null
        }
        if (radiusPx != lastRadiusPx) {
            lastRadiusPx = radiusPx
            cached = AndroidRenderEffect
                .createBlurEffect(radiusPx, radiusPx, AndroidShader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
        return cached
    }
}

/**
 * Returns a cached Shape instance for a quantized (top, bottom) radius pair.
 * Because the instance identity is stable while the radii don't move past a
 * sub-pixel threshold, the graphics layer reuses its cached Outline between
 * frames and only re-clips when the radius actually changes.
 */
private class NavBarShapeCache {
    private var lastTopPx: Float = Float.NaN
    private var lastBottomPx: Float = Float.NaN
    private var lastSmooth: Boolean = true
    private var cached: androidx.compose.ui.graphics.Shape = RectangleShape

    fun get(
        density: androidx.compose.ui.unit.Density,
        topPx: Float,
        bottomPx: Float,
        smooth: Boolean
    ): androidx.compose.ui.graphics.Shape {
        if (smooth == lastSmooth &&
            !lastTopPx.isNaN() &&
            kotlin.math.abs(topPx - lastTopPx) < 0.5f &&
            kotlin.math.abs(bottomPx - lastBottomPx) < 0.5f
        ) {
            return cached
        }
        lastTopPx = topPx
        lastBottomPx = bottomPx
        lastSmooth = smooth
        cached = with(density) {
            DynamicSmoothCornerShape(
                useSmoothCorners = smooth,
                topRadius = topPx.toDp(),
                bottomRadius = bottomPx.toDp()
            )
        }
        return cached
    }
}

/**
 * Fixed-radius corner shape. Swaps AbsoluteSmoothCornerShape for a plain
 * RoundedCornerShape when smooth corners are disabled in settings. The radius
 * values are identical in both branches, so the animated radius behavior is
 * unchanged regardless of which delegate is active. The resulting Outline is
 * cached per (size, layoutDirection) so repeated draws are cheap.
 */
private class DynamicSmoothCornerShape(
    private val useSmoothCorners: Boolean,
    private val topRadius: androidx.compose.ui.unit.Dp,
    private val bottomRadius: androidx.compose.ui.unit.Dp
) : androidx.compose.ui.graphics.Shape {

    private var cachedSize: androidx.compose.ui.geometry.Size =
        androidx.compose.ui.geometry.Size.Unspecified
    private var cachedLayoutDirection: androidx.compose.ui.unit.LayoutDirection? = null
    private var cachedOutline: androidx.compose.ui.graphics.Outline? = null

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        cachedOutline?.let {
            if (cachedSize == size && cachedLayoutDirection == layoutDirection) return it
        }

        val delegate: androidx.compose.ui.graphics.Shape = if (useSmoothCorners) {
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = topRadius,
                smoothnessAsPercentTL = 60,
                cornerRadiusTR = topRadius,
                smoothnessAsPercentTR = 60,
                cornerRadiusBL = bottomRadius,
                smoothnessAsPercentBL = 60,
                cornerRadiusBR = bottomRadius,
                smoothnessAsPercentBR = 60
            )
        } else {
            RoundedCornerShape(
                topStart = topRadius,
                topEnd = topRadius,
                bottomEnd = bottomRadius,
                bottomStart = bottomRadius
            )
        }

        return delegate.createOutline(size, layoutDirection, density).also {
            cachedSize = size
            cachedLayoutDirection = layoutDirection
            cachedOutline = it
        }
    }
}
