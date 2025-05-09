/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.yank

import com.maddyhome.idea.vim.action.motion.updown.MotionDownLess1FirstNonSpaceAction
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.ImmutableVimCaret
import com.maddyhome.idea.vim.api.VimCaret
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.anyNonWhitespace
import com.maddyhome.idea.vim.api.getLineEndForOffset
import com.maddyhome.idea.vim.api.getLineStartForOffset
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.MotionType
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.handler.MotionActionHandler
import com.maddyhome.idea.vim.state.mode.SelectionType
import kotlin.math.min

open class YankGroupBase : VimYankGroup {
  private fun yankRange(
    editor: VimEditor,
    context: ExecutionContext,
    caretToRange: Map<ImmutableVimCaret, Pair<TextRange, SelectionType>>,
    startOffsets: Map<VimCaret, Int>?,
  ): Boolean {
    startOffsets?.forEach { (caret, offset) ->
      caret.moveToOffset(offset)
    }

    injector.listenersNotifier.notifyYankPerformed(caretToRange.mapValues { it.value.first })

    var result = true
    for ((caret, myRange) in caretToRange) {
      result = caret.registerStorage.storeText(editor, context, myRange.first, myRange.second, false) && result
    }
    return result
  }

  /**
   * This yanks the text moved over by the motion command argument.
   */
  override fun yankMotion(
    editor: VimEditor,
    context: ExecutionContext,
    argument: Argument,
    operatorArguments: OperatorArguments,
  ): Boolean {
    val motion = argument as? Argument.Motion ?: return false

    val nativeCaretCount = editor.nativeCarets().size
    if (nativeCaretCount <= 0) return false

    val caretToRange = HashMap<ImmutableVimCaret, Pair<TextRange, SelectionType>>(nativeCaretCount)

    // This logic is from original vim
    val startOffsets =
      if (argument.motion is MotionDownLess1FirstNonSpaceAction) {
        null
      } else {
        HashMap<VimCaret, Int>(nativeCaretCount)
      }


    for (caret in editor.nativeCarets()) {
      var motionType = motion.getMotionType()
      val motionRange = injector.motion.getMotionRange(editor, caret, context, argument, operatorArguments)
        ?: continue

      assert(motionRange.size() == 1)
      startOffsets?.put(caret, motionRange.normalize().startOffset)

      // Yank motion commands that are not linewise become linewise if all the following are true:
      // 1) The range is across multiple lines
      // 2) There is only whitespace before the start of the range
      // 3) There is only whitespace after the end of the range
      if (argument.motion is MotionActionHandler && argument.motion.motionType == MotionType.EXCLUSIVE) {
        val start = editor.offsetToBufferPosition(motionRange.startOffset)
        val end = editor.offsetToBufferPosition(motionRange.endOffset)
        if (start.line != end.line
          && !editor.anyNonWhitespace(motionRange.startOffset, -1)
          && !editor.anyNonWhitespace(motionRange.endOffset, 1)
        ) {
          motionType = SelectionType.LINE_WISE
        }
      }

      caretToRange[caret] = TextRange(motionRange.startOffset, motionRange.endOffset) to motionType
    }

    if (caretToRange.isEmpty()) return false

    return yankRange(
      editor,
      context,
      caretToRange,
      startOffsets,
    )
  }

  /**
   * This yanks count lines of text
   *
   * @param editor The editor to yank from
   * @param count  The number of lines to yank
   * @return true if able to yank the lines, false if not
   */
  override fun yankLine(editor: VimEditor, context: ExecutionContext, count: Int): Boolean {
    val caretCount = editor.nativeCarets().size
    val caretToRange = HashMap<ImmutableVimCaret, Pair<TextRange, SelectionType>>(caretCount)
    for (caret in editor.nativeCarets()) {
      val start = injector.motion.moveCaretToCurrentLineStart(editor, caret)
      val end =
        min(injector.motion.moveCaretToRelativeLineEnd(editor, caret, count - 1, true) + 1, editor.fileSize().toInt())

      if (end == -1) continue

      caretToRange[caret] = TextRange(start, end) to SelectionType.LINE_WISE
    }

    return yankRange(editor, context, caretToRange, null)
  }

  /**
   * This yanks a range of text
   *
   * @param editor The editor to yank from
   * @param range  The range of text to yank
   * @param type   The type of yank
   * @return true if able to yank the range, false if not
   */
  override fun yankRange(
    editor: VimEditor,
    context: ExecutionContext,
    range: TextRange?,
    type: SelectionType,
    moveCursor: Boolean,
  ): Boolean {
    range ?: return false
    val caretToRange = HashMap<ImmutableVimCaret, Pair<TextRange, SelectionType>>()

    if (type == SelectionType.LINE_WISE) {
      for (i in 0 until range.size()) {
        if (editor.offsetToBufferPosition(range.startOffsets[i]).column != 0) {
          range.startOffsets[i] = editor.getLineStartForOffset(range.startOffsets[i])
        }
        if (editor.offsetToBufferPosition(range.endOffsets[i]).column != 0) {
          range.endOffsets[i] =
            (editor.getLineEndForOffset(range.endOffsets[i]) + 1).coerceAtMost(editor.fileSize().toInt())
        }
      }
    }

    val rangeStartOffsets = range.startOffsets
    val rangeEndOffsets = range.endOffsets

    val startOffsets = HashMap<VimCaret, Int>(editor.nativeCarets().size)
    if (type == SelectionType.BLOCK_WISE) {
      startOffsets[editor.primaryCaret()] = range.normalize().startOffset
      caretToRange[editor.primaryCaret()] = range to type
    } else {
      for ((i, caret) in editor.nativeCarets().withIndex()) {
        val textRange = TextRange(rangeStartOffsets[i], rangeEndOffsets[i])
        startOffsets[caret] = textRange.normalize().startOffset
        caretToRange[caret] = textRange to type
      }
    }

    return if (moveCursor) {
      yankRange(editor, context, caretToRange, startOffsets)
    } else {
      yankRange(editor, context, caretToRange, null)
    }
  }
}
