package com.lookbuddy.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lookbuddy.app.OnboardingDeviceFont

// Set of Material typography styles to start with
private val BaseTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)

val Typography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = OnboardingDeviceFont),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = OnboardingDeviceFont),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = OnboardingDeviceFont),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = OnboardingDeviceFont),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = OnboardingDeviceFont),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = OnboardingDeviceFont),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = OnboardingDeviceFont),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = OnboardingDeviceFont),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = OnboardingDeviceFont),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = OnboardingDeviceFont),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = OnboardingDeviceFont),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = OnboardingDeviceFont),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = OnboardingDeviceFont),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = OnboardingDeviceFont),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = OnboardingDeviceFont)
)
