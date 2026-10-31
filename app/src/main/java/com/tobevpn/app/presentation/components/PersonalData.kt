package com.tobevpn.app.presentation.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Admins only: hide personal data (Telegram photo, name, ID, email, referral
 * link and names, device names) for screenshots and screen sharing. Provided
 * once by the navigation host; already false for non-admin accounts.
 */
val LocalPersonalDataHidden = compositionLocalOf { false }

/** Stands in for a hidden value. */
const val PERSONAL_DATA_MASK = "••••••"
