package com.github.antsrc.ant.editing

import com.github.antsrc.ant.AntLanguage
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/** The defaults of `ant fmt`: two spaces per level, never a tab (fmt.md section 4). */
class AntCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = AntLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.TAB_SIZE = 2
        indentOptions.USE_TAB_CHARACTER = false
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.INDENT_SETTINGS) {
            consumer.showStandardOptions("INDENT_SIZE", "TAB_SIZE", "USE_TAB_CHARACTER")
        }
    }

    override fun getCodeSample(settingsType: SettingsType) = """
        func area(s: Shape): float64 {
          if s is Circle {
            return Math.pi * s.r * s.r
          }
          return s.w * s.h
        }
    """.trimIndent()
}
