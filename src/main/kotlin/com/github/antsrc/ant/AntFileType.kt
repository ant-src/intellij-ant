package com.github.antsrc.ant

import com.intellij.openapi.fileTypes.LanguageFileType

object AntFileType : LanguageFileType(AntLanguage) {
    override fun getName() = "Ant"
    override fun getDescription() = "Ant source file"
    override fun getDefaultExtension() = "ant"
    override fun getIcon() = AntIcons.FILE

    /** The end of a test file's name (ant-lang.md section 10, Tests). */
    const val TEST_SUFFIX = "_test.ant"

    fun isTestFile(path: String) = path.endsWith(TEST_SUFFIX)
}
