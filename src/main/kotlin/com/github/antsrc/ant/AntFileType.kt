package com.github.antsrc.ant

import com.intellij.openapi.fileTypes.LanguageFileType

object AntFileType : LanguageFileType(AntLanguage) {
    override fun getName() = "Ant"
    override fun getDescription() = "Ant source file"
    override fun getDefaultExtension() = "ant"
    override fun getIcon() = AntIcons.FILE
}
