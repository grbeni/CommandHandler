package inc;

import org.yakindu.base.expressions.expressions.AssignmentExpression;
import org.yakindu.base.expressions.expressions.BitwiseAndExpression;
import org.yakindu.base.expressions.expressions.BitwiseOrExpression;
import org.yakindu.base.expressions.expressions.BitwiseXorExpression;
import org.yakindu.base.expressions.expressions.BoolLiteral;
import org.yakindu.base.expressions.expressions.ElementReferenceExpression;
import org.yakindu.base.expressions.expressions.Expression;
import org.yakindu.base.expressions.expressions.FeatureCall;
import org.yakindu.base.expressions.expressions.IntLiteral;
import org.yakindu.base.expressions.expressions.LogicalAndExpression;
import org.yakindu.base.expressions.expressions.LogicalNotExpression;
import org.yakindu.base.expressions.expressions.LogicalOrExpression;
import org.yakindu.base.expressions.expressions.LogicalRelationExpression;
import org.yakindu.base.expressions.expressions.NumericalAddSubtractExpression;
import org.yakindu.base.expressions.expressions.NumericalMultiplyDivideExpression;
import org.yakindu.base.expressions.expressions.NumericalUnaryExpression;
import org.yakindu.base.expressions.expressions.ParenthesizedExpression;
import org.yakindu.base.expressions.expressions.PrimitiveValueExpression;
import org.yakindu.base.expressions.expressions.ShiftExpression;
import org.yakindu.sct.model.stext.stext.EventDefinition;
import org.yakindu.sct.model.stext.stext.EventRaisingExpression;
import org.yakindu.sct.model.stext.stext.EventValueReferenceExpression;
import org.yakindu.sct.model.stext.stext.VariableDefinition;

public class UppaalCodeGenerator {
	
	/**
	 * A Yakindu expressionöket UPPAAL szabályoknak megfelelõ kifejezésekké transzformáló metódus.
	 * @param expression A Yakindu expression, amelyet transzformálni kell.
	 * @return Egy string, amely a transzformált kifejezést tartalmazza, UPPAAL szabályoknak megfelelõen.
	 */
	public static String transformExpression(Expression expression) {
		if (expression == null) {
			return "";
		}
		// Ha a kifejezés egy egyoperandusú numerikus kifejezéssel kezdõdik, akkor leírom
		// az operátort, majd letranszformálom a maradék kifejezést is.
		// Visszaadom, amit kaptam.
		else if (expression instanceof NumericalUnaryExpression) {
			NumericalUnaryExpression NUExpression = (NumericalUnaryExpression) expression;
			switch (NUExpression.getOperator().getValue()) {
			case 0:
				return " +" + transformExpression(NUExpression.getOperand());
			case 1:
				return " -" + transformExpression(NUExpression.getOperand());
			case 2:
				return " (-1 ^" + transformExpression(NUExpression.getOperand()) + ")"; // Az invertálás: 11..11-gyel való xor mûvelet: UPPAAL-ban nincs ~operátor
			default:
				return "";
			}
		}
		// Összeadás/kivonás kifejezés
		else if (expression instanceof NumericalAddSubtractExpression) {
			NumericalAddSubtractExpression addSubtract = (NumericalAddSubtractExpression) expression;
			return transformExpression(addSubtract.getLeftOperand()) + " " + addSubtract.getOperator().getLiteral() + transformExpression(addSubtract.getRightOperand());
		}
		// Szorzás/osztás kifejezés
		else if (expression instanceof NumericalMultiplyDivideExpression) {
			NumericalMultiplyDivideExpression multiplyDivide = (NumericalMultiplyDivideExpression) expression;
			return transformExpression(multiplyDivide.getLeftOperand()) + " " + multiplyDivide.getOperator().getLiteral() + transformExpression(multiplyDivide.getRightOperand());
		}
		// Bitshiftelõ kifejezés
		else if (expression instanceof ShiftExpression) {
			ShiftExpression shift = (ShiftExpression) expression;
			return transformExpression(shift.getLeftOperand()) + " " + shift.getOperator().getLiteral() + transformExpression(shift.getRightOperand());
		}
		// Bitenkénti AND
		else if (expression instanceof BitwiseAndExpression) {
			BitwiseAndExpression and = (BitwiseAndExpression) expression;
			return transformExpression(and.getLeftOperand()) + " &amp; " + transformExpression(and.getRightOperand());
		}
		// Bitenkénti OR
		else if (expression instanceof BitwiseOrExpression) {
			BitwiseOrExpression or = (BitwiseOrExpression) expression;
			return transformExpression(or.getLeftOperand()) + " | " + transformExpression(or.getRightOperand());
		}
		// Bitenkénti XOR
		else if (expression instanceof BitwiseXorExpression) {
			BitwiseXorExpression xor = (BitwiseXorExpression) expression;
			return transformExpression(xor.getLeftOperand()) + " ^ " + transformExpression(xor.getRightOperand());
		}
		// Sima egyszerû értékadás esetén leírom az értéket, majd visszaadom
		else if (expression instanceof PrimitiveValueExpression) {
			PrimitiveValueExpression PVExpression = (PrimitiveValueExpression) expression;
			// Ha az expression egy integert tartalmaz, akkor visszaadom azt
			if (PVExpression.getValue() instanceof IntLiteral) {
				IntLiteral intLiteral = (IntLiteral) PVExpression.getValue();
				return " " + intLiteral.getValue();
			}
			// Ha az expression egy booleant tartalmat, akkor visszaadom azt
			else if (PVExpression.getValue() instanceof BoolLiteral) {
				BoolLiteral boolLiteral = (BoolLiteral) PVExpression.getValue();
				return " " + boolLiteral.isValue();
			} 
			// Más típusú változót még nem tudunk átvinni UPPAALba
			else {
				throw new UnsupportedOperationException(PVExpression.getValue().getClass().toGenericString());
			}
		}
		// Értékadás esetén: Megnézem a bal oldalt, kiteszek egy = jelet, majd megnézem a jobb oldalt.
		else if (expression instanceof AssignmentExpression) {
			return transformExpression(((AssignmentExpression) expression).getVarRef()) + "  " + ((AssignmentExpression) expression).getOperator().getLiteral() + " " + transformExpression(((AssignmentExpression) expression).getExpression());
		}
		// Ha a kifejezés egy FeatureCall (pl.: Server.value), akkor megnézem a változó nevét
		// Interfész referenciával nem foglalkozunk
		else if (expression instanceof FeatureCall) {
			FeatureCall featureCall = (FeatureCall) expression;
			if (featureCall.getFeature() instanceof VariableDefinition) {
				VariableDefinition variable = (VariableDefinition) featureCall.getFeature();
				return variable.getName();
			}
			if (featureCall.getFeature() instanceof EventDefinition) {
				EventDefinition variable = (EventDefinition) featureCall.getFeature();
				return variable.getName();
			}
			throw new UnsupportedOperationException("Nem ismert kifejezés. :( " + expression.getClass().toGenericString());
		}
		// Ha a kifejezés egy elemhivatkozás, megnézem az elemet, és ha változó, visszaadom a nevét
		else if (expression instanceof ElementReferenceExpression) {
			ElementReferenceExpression elementReference = (ElementReferenceExpression) expression;
			if (elementReference.getReference() instanceof VariableDefinition) {
				VariableDefinition variable = (VariableDefinition) elementReference.getReference();
				return variable.getName();
			}
			if (elementReference.getReference() instanceof EventDefinition) {
				EventDefinition event = (EventDefinition) elementReference.getReference();
				return event.getName();
			}
			throw new UnsupportedOperationException("Nem ismert kifejezés. :( " + expression.getClass().toGenericString());
		}
		// Ha a kifejezés egy logikai reláció: Transzformálom a baloldalt, kiírom a relációs jelet, majd transzformálom a jobboldalt
		else if (expression instanceof LogicalRelationExpression) {
			LogicalRelationExpression logicalRelation = (LogicalRelationExpression) expression;
			return transformExpression(logicalRelation.getLeftOperand()) + " " + logicalRelation.getOperator().getLiteral() + " " + transformExpression(logicalRelation.getRightOperand());
		}
		// Ha a kifejezés egy logikai OR: Transzformálom a baloldalt, kiírom a relációs jelet, majd transzformálom a jobboldalt
		else if (expression instanceof LogicalOrExpression) {
			LogicalOrExpression logicalOr = (LogicalOrExpression) expression;
			return transformExpression(logicalOr.getLeftOperand()) + " || " + transformExpression(logicalOr.getRightOperand()); 
		}
		// Ha a kifejezés egy logikai OR: Transzformálom a baloldalt, kiírom a relációs jelet, majd transzformálom a jobboldalt
		else if (expression instanceof LogicalAndExpression) {
			LogicalAndExpression logicalAnd = (LogicalAndExpression) expression;
			return transformExpression(logicalAnd.getLeftOperand()) + " &amp;&amp;" + transformExpression(logicalAnd.getRightOperand()); 
		}
		// Ha a kifejezés egy logikai NOT: Kiírom a ! jelet, majd transzformálom az operandust
		else if (expression instanceof LogicalNotExpression) {
			LogicalNotExpression logicalNot = (LogicalNotExpression) expression;
			return " !" + transformExpression(logicalNot.getOperand());
		}
		// Ha egy zárójeles kifejezés: kiteszek zárójeleket, és a köztük lévõ kifejezést transzformálom
		else if (expression instanceof ParenthesizedExpression) {
			ParenthesizedExpression parent = (ParenthesizedExpression) expression;
			return "(" + transformExpression(parent.getExpression()) + ")";
		}
		else if (expression instanceof EventRaisingExpression) {
			// Ekkor nem a transitionre írunk rá, hanem sync csatornát hozunk létre
			// lsd.: createRaisingEventSyncs() metódus
			return "";
		}
		else if (expression instanceof EventValueReferenceExpression) {
			return transformExpression(((EventValueReferenceExpression) expression).getValue());
		}
		// Különben nem ismert kifejezés kivételt dobok
		else {
			throw new UnsupportedOperationException("Nem ismert kifejezés. :( " + expression.getClass().toGenericString());
		}
	}

}
