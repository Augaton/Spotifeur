package fr.spotifeur

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import fr.spotifeur.presentation.navigation.Screen

data class BottomNavItem(
    val label: String,
    @StringRes val labelResId: Int,
    @DrawableRes val iconResId: Int,
    @DrawableRes val selectedIconResId: Int? = null,
    val screen: Screen,
)
