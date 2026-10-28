package com.github.antsrc.ant.psi

import com.github.antsrc.ant.AntFileType
import com.github.antsrc.ant.AntLanguage
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider

class AntFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AntLanguage) {
    override fun getFileType() = AntFileType
    override fun toString() = "Ant file"
}
