/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim.action.change.change

import com.intellij.vim.annotations.CommandOrMotion
import com.intellij.vim.annotations.Mode
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimChangeGroup
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.group.visual.VimSelection
import com.maddyhome.idea.vim.handler.VisualOperatorActionHandler

/**
 * @author vlan
 *
 * Note: This implementation assumes that the 'gU' command in visual mode is equivalent to 'U'.
 * While 'v_gU' is not explicitly documented in Vim help, we treat these commands as identical
 * based on observed behavior, without examining Vim's source code.
 */
@CommandOrMotion(keys = ["U", "gU"], modes = [Mode.VISUAL])
class ChangeCaseUpperVisualAction : VisualOperatorActionHandler.ForEachCaret() {
  override val type: Command.Type = Command.Type.CHANGE

  override fun executeAction(
    editor: VimEditor,
    caret: VimCaret,
    context: ExecutionContext,
    cmd: Command,
    range: VimSelection,
    operatorArguments: OperatorArguments,
  ): Boolean {
    return injector.changeGroup
      .changeCaseRange(editor, caret, range.toVimTextRange(false), VimChangeGroup.ChangeCaseType.UPPER)
  }
}
