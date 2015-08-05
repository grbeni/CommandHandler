package inc

import org.yakindu.base.expressions.expressions.Expression
import org.eclipse.ui.internal.expressions.AndExpression
import org.yakindu.base.expressions.expressions.LogicalAndExpression
import org.yakindu.base.expressions.expressions.LogicalOrExpression
import org.yakindu.base.expressions.expressions.LogicalRelationExpression
import org.yakindu.base.expressions.expressions.RelationalOperator
import org.yakindu.base.expressions.expressions.PrimitiveValueExpression
import org.yakindu.base.expressions.expressions.BoolLiteral
import org.yakindu.base.expressions.expressions.ElementReferenceExpression

class ExpressionTransformer {
	
	var processes = 0;
	def nextProcessId() {
		processes+=1;
		return processes
	}
	
	def dispatch CharSequence transform(LogicalAndExpression and) '''«and.leftOperand.transform» && «and.rightOperand.transform»'''
	def dispatch CharSequence transform(LogicalOrExpression or) '''«or.leftOperand.transform» || «or.rightOperand.transform»'''
	def dispatch CharSequence transform(LogicalRelationExpression relation)
		'''«relation.leftOperand.transform» «relation.operator.transformOperator» «relation.rightOperand.transform»'''
	def dispatch CharSequence transform(PrimitiveValueExpression primitive) {
		return primitive.value.toString
	}
	def dispatch CharSequence transform(ElementReferenceExpression expression) {
		//expression.reference
	}
	
	def transformOperator(RelationalOperator operator) {
		switch(operator) {
			case EQUALS: '''=='''
			case NOT_EQUALS: '''!='''
			default: throw new UnsupportedOperationException
		}
	}
}