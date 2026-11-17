package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.Vec2
import com.bsoft.bhandbook.core.recognition.InkSignature
import com.bsoft.bhandbook.core.recognition.Pen
import com.bsoft.bhandbook.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CanvasEditorTest {
    private var counter = 0
    private fun editor() = CanvasEditor(idGenerator = { "e${++counter}" }, clock = { 1000L })
    private fun CanvasEditor.draw(strokes: List<Stroke>) = strokes.forEach { addStroke(it) }

    // ------------------------------------------------------------ association & grouping
    @Test fun firstStroke_createsAnExpression_andIsAssociatedWithIt() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single())
        assertEquals(1, ed.state.expressions.size)
        assertEquals(ed.state.expressions.keys.single(), ed.state.strokes.single().expressionId)
        assertEquals(ProcessingStatus.PENDING, ed.state.expressions.values.single().status)
    }
    @Test fun strokesOfOneWrittenExpression_shareOneExpression() {
        val ed = editor(); val ink = Pen(2).text("25+15=").strokes; ed.draw(ink)
        assertEquals(1, ed.state.expressions.size); assertEquals(ink.size, ed.state.strokes.size)
        assertEquals(1, ed.state.strokes.map { it.expressionId }.toSet().size)
    }
    @Test fun separateExpressions_getSeparateObjects_withoutRearrangingEachOther() {
        val ed = editor()
        ed.draw(Pen(1).at(100f, 300f).text("12+3").strokes)
        val firstBounds = ed.state.boundsOf(ed.state.expressions.keys.first())
        ed.draw(Pen(2).at(100f, 600f).text("9-4").strokes)
        assertEquals(2, ed.state.expressions.size)
        assertEquals(firstBounds, ed.state.boundsOf(ed.state.expressions.keys.first()))   // untouched
    }
    @Test fun bridgingStroke_mergesTwoExpressions_andUndoSplitsThemAgain() {
        val ed = editor()
        val a = Pen(1).at(100f, 300f).text("12").strokes; ed.draw(a)
        val b = Pen(2).at(a.maxOf { it.bounds.right } + 170f, 300f).text("34").strokes; ed.draw(b)
        assertEquals(2, ed.state.expressions.size)
        ed.addStroke(Pen(3).at(a.maxOf { it.bounds.right } + 55f, 300f).text("+").strokes.first())
        assertEquals(1, ed.state.expressions.size)
        ed.undo()
        assertEquals(2, ed.state.expressions.size)
        assertEquals(a.size + b.size, ed.state.strokes.size)
        assertEquals(setOf(a.size, b.size), ed.state.strokes.groupBy { it.expressionId }.values.map { it.size }.toSet())
    }

    // ------------------------------------------------------------ undo / redo
    @Test fun undoRedo_ofAStroke_includingItsExpression() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single())
        ed.undo(); assertTrue(ed.state.strokes.isEmpty()); assertTrue(ed.state.expressions.isEmpty())
        ed.redo(); assertEquals(1, ed.state.strokes.size); assertEquals(1, ed.state.expressions.size)
    }
    @Test fun undoWithNothingToUndo_isANoOp() { assertNull(editor().undo()); assertFalse(editor().canUndo) }
    @Test fun newEdit_afterUndo_discardsRedo() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single()); ed.undo()
        ed.addStroke(Pen(2).text("1").strokes.single()); assertFalse(ed.canRedo)
    }

    // ------------------------------------------------------------ erasing
    @Test fun eraseGesture_isOneUndoStep_andRestoresExactInk() {
        val ed = editor(); val ink = Pen(1).text("100").strokes; ed.draw(ink)
        val before = ed.state.strokes
        ed.beginErase(); ed.eraseAt(Vec2(ink.first().bounds.centerX, ink.first().bounds.centerY), 12f)
        ed.eraseAt(Vec2(ink.last().bounds.centerX, ink.last().bounds.centerY), 12f); ed.endErase()
        assertNotEquals(before, ed.state.strokes)
        ed.undo(); assertEquals(before, ed.state.strokes)
        ed.redo(); assertNotEquals(before, ed.state.strokes)
    }
    @Test fun partialErase_splitsAStroke_keepingExpressionAssociation() {
        val ed = editor()
        val line = Stroke("L", (0..40).map { StrokePoint(100f + it * 5f, 300f, it.toLong()) }, 4f, 0)
        ed.addStroke(line)
        ed.beginErase(); ed.eraseAt(Vec2(200f, 300f), 10f); ed.endErase()
        assertEquals(2, ed.state.strokes.size)
        assertEquals(1, ed.state.strokes.map { it.expressionId }.toSet().size)
    }
    @Test fun erasingEverything_removesTheExpressionObject_andUndoBringsItBack() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single())
        val b = ed.state.strokes.single().bounds
        ed.beginErase(); ed.eraseAt(Vec2(b.centerX, b.centerY), 400f); ed.endErase()
        assertTrue(ed.state.strokes.isEmpty()); assertTrue(ed.state.expressions.isEmpty())
        ed.undo(); assertEquals(1, ed.state.strokes.size); assertEquals(1, ed.state.expressions.size)
    }
    @Test fun erasingPartOfAnExpression_invalidatesItsResult() {
        val ed = editor(); ed.draw(Pen(1).text("25+15=").strokes)
        val id = ed.state.expressions.keys.single()
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(recognized = "25+15=", result = "40", status = ProcessingStatus.EVALUATED) }
        val s = ed.state.strokes.first().bounds
        ed.beginErase(); ed.eraseAt(Vec2(s.centerX, s.centerY), 10f); ed.endErase()
        assertNull(ed.state.expressions[id]!!.result); assertEquals(ProcessingStatus.PENDING, ed.state.expressions[id]!!.status)
        assertTrue(id in ed.expressionsNeedingRecognition())
    }

    // ------------------------------------------------------------ move / delete / clear
    @Test fun moveExpression_movesOnlyItsInk_andIsUndoable() {
        val ed = editor(); ed.draw(Pen(1).at(100f, 300f).text("12").strokes); ed.draw(Pen(2).at(100f, 700f).text("34").strokes)
        val (first, second) = ed.state.expressions.keys.toList()
        val secondBefore = ed.state.boundsOf(second); val firstBefore = ed.state.boundsOf(first)
        ed.moveExpression(first, 500f, -40f)
        assertEquals(firstBefore.translated(500f, -40f).left, ed.state.boundsOf(first).left, 0.01f)
        assertEquals(secondBefore, ed.state.boundsOf(second))
        ed.undo(); assertEquals(firstBefore.left, ed.state.boundsOf(first).left, 0.01f)
    }
    @Test fun moveExpression_doesNotRequireRerecognition() {
        val ed = editor(); ed.draw(Pen(1).text("25+15").strokes); val id = ed.state.expressions.keys.single()
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(result = "40", status = ProcessingStatus.EVALUATED) }
        ed.moveExpression(id, 900f, 900f)
        assertTrue(ed.expressionsNeedingRecognition().isEmpty())
        assertEquals("40", ed.state.expressions[id]!!.result)
    }
    @Test fun deleteExpression_andUndo() {
        val ed = editor(); ed.draw(Pen(1).text("12").strokes); val id = ed.state.expressions.keys.single()
        ed.deleteExpression(id); assertTrue(ed.state.strokes.isEmpty()); ed.undo(); assertTrue(ed.state.strokes.isNotEmpty()); assertTrue(id in ed.state.expressions)
    }
    @Test fun clearAll_isUndoable_andRestoresEverything() {
        val ed = editor(); ed.draw(Pen(1).at(100f, 300f).text("12").strokes); ed.draw(Pen(2).at(100f, 800f).text("34").strokes)
        val before = ed.state; ed.clearAll()
        assertTrue(ed.state.strokes.isEmpty()); assertTrue(ed.state.expressions.isEmpty())
        ed.undo(); assertEquals(before.strokes, ed.state.strokes); assertEquals(before.expressions.keys, ed.state.expressions.keys)
    }

    // ------------------------------------------------------------ recognition bookkeeping
    @Test fun newInk_needsRecognition_andResultsClearThat() {
        val ed = editor(); ed.draw(Pen(1).text("25+15").strokes); val id = ed.state.expressions.keys.single()
        assertEquals(setOf(id), ed.expressionsNeedingRecognition())
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(result = "40", status = ProcessingStatus.EVALUATED) }
        assertTrue(ed.expressionsNeedingRecognition().isEmpty())
    }
    @Test fun addingInk_invalidatesPreviousResult() {
        val ed = editor(); val ink = Pen(1).text("25+1").strokes; ed.draw(ink); val id = ed.state.expressions.keys.single()
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(recognized = "25+1", result = "26", status = ProcessingStatus.EVALUATED) }
        ed.addStroke(Pen(2).at(ink.maxOf { it.bounds.right } + 15f, 300f).text("5").strokes.first())
        assertNull(ed.state.expressions[id]!!.result); assertEquals(ProcessingStatus.PENDING, ed.state.expressions[id]!!.status)
    }
    @Test fun staleRecognitionResults_areDiscarded() {
        val ed = editor(); val ink = Pen(1).text("25+1").strokes; ed.draw(ink); val id = ed.state.expressions.keys.single()
        val staleSig = ed.signatureOf(id)
        ed.addStroke(Pen(2).at(ink.maxOf { it.bounds.right } + 15f, 300f).text("7").strokes.single())
        assertFalse(ed.applyRecognition(id, staleSig) { it.copy(result = "26") })
        assertNull(ed.state.expressions[id]!!.result)
    }
    @Test fun undoBackToRecognisedInk_needsNoNewRecognition() {
        val ed = editor(); val ink = Pen(1).text("25+1").strokes; ed.draw(ink); val id = ed.state.expressions.keys.single()
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(result = "26", status = ProcessingStatus.EVALUATED) }
        ed.addStroke(Pen(2).at(ink.maxOf { it.bounds.right } + 15f, 300f).text("7").strokes.single())
        ed.undo()
        assertTrue("undoing to already-recognised ink must not request recognition", ed.expressionsNeedingRecognition().isEmpty())
        assertEquals("26", ed.state.expressions[id]!!.result)
    }
    @Test fun recognitionResults_areNotUserUndoSteps() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single()); val id = ed.state.expressions.keys.single()
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(result = "7") }
        ed.undo(); assertTrue(ed.state.strokes.isEmpty()); assertFalse(ed.canUndo)
    }
    @Test fun userEdits_toExpression_areUndoable() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single()); val id = ed.state.expressions.keys.single()
        ed.updateExpression(id) { it.copy(solutionVisible = false) }; assertFalse(ed.state.expressions[id]!!.solutionVisible)
        ed.undo(); assertTrue(ed.state.expressions[id]!!.solutionVisible)
    }
    @Test fun recalculate_requestsRecognition_evenWhenInkIsUnchanged() {
        val ed = editor(); ed.draw(Pen(1).text("25+15").strokes); val id = ed.state.expressions.keys.single()
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(result = "40", status = ProcessingStatus.EVALUATED) }
        assertTrue(ed.expressionsNeedingRecognition().isEmpty())
        ed.invalidateExpression(id)
        assertEquals(setOf(id), ed.expressionsNeedingRecognition()); assertNull(ed.state.expressions[id]!!.result)
        ed.undo(); assertEquals("40", ed.state.expressions[id]!!.result); assertTrue(ed.expressionsNeedingRecognition().isEmpty())
    }
    @Test fun markRecognizing_showsProgress_withoutCreatingAnUndoStep() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single()); val id = ed.state.expressions.keys.single()
        assertEquals(ProcessingStatus.PENDING, ed.state.expressions[id]!!.status)
        assertTrue(ed.markRecognizing(id, ed.signatureOf(id)))
        assertEquals(ProcessingStatus.RECOGNIZING, ed.state.expressions[id]!!.status)
        ed.undo()
        assertTrue(ed.state.strokes.isEmpty()); assertTrue(ed.state.expressions.isEmpty())
        assertFalse("the progress marker must not have added an undo step", ed.canUndo)
    }
    @Test fun markRecognizing_ignoresStaleRequests_andNeverOverwritesFinishedResults() {
        val ed = editor(); val ink = Pen(1).text("25+1").strokes; ed.draw(ink); val id = ed.state.expressions.keys.single()
        val stale = ed.signatureOf(id)
        ed.addStroke(Pen(2).at(ink.maxOf { it.bounds.right } + 15f, 300f).text("7").strokes.single())
        assertFalse(ed.markRecognizing(id, stale)); assertEquals(ProcessingStatus.PENDING, ed.state.expressions[id]!!.status)
        ed.applyRecognition(id, ed.signatureOf(id)) { it.copy(result = "32", status = ProcessingStatus.EVALUATED) }
        assertFalse("a finished expression is not flipped back to 'recognizing'", ed.markRecognizing(id, ed.signatureOf(id)))
        assertEquals(ProcessingStatus.EVALUATED, ed.state.expressions[id]!!.status)
    }
    @Test fun markRecognizing_forAMissingExpression_isANoOp() { assertFalse(editor().markRecognizing("nope", 1)) }
    @Test fun loadState_resetsHistory() {
        val ed = editor(); ed.addStroke(Pen(1).text("7").strokes.single()); ed.load(CanvasState()); assertFalse(ed.canUndo); assertTrue(ed.state.strokes.isEmpty())
    }
    @Test fun signature_matchesInkSignatureOfTheExpressionsStrokes() {
        val ed = editor(); ed.draw(Pen(1).text("12").strokes); val id = ed.state.expressions.keys.single()
        assertEquals(InkSignature.of(ed.state.strokesOf(id)), ed.signatureOf(id))
    }
}
