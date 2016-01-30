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
	 * A Yakindu expression�ket UPPAAL szab�lyoknak megfelel� kifejez�sekk� transzform�l� met�dus.
	 * @param expression A Yakindu expression, amelyet transzform�lni kell.
	 * @return Egy string, amely a transzform�lt kifejez�st tartalmazza, UPPAAL szab�lyoknak megfelel�en.
	 */
	public static String transformExpression(Expression expression) {
		if (expression == null) {
			return "";
		}
		// Ha a kifejez�s egy egyoperandus� numerikus kifejez�ssel kezd�dik, akkor le�rom
		// az oper�tort, majd letranszform�lom a marad�k kifejez�st is.
		// Visszaadom, amit kaptam.
		else if (expression instanceof NumericalUnaryExpression) {
			NumericalUnaryExpression NUExpression = (NumericalUnaryExpression) expression;
			switch (NUExpression.getOperator().getValue()) {
			case 0:
				return " +" + transformExpression(NUExpression.getOperand());
			case 1:
				return " -" + transformExpression(NUExpression.getOperand());
			case 2:
				return " (-1 ^" + transformExpression(NUExpression.getOperand()) + ")"; // Az invert�l�s: 11..11-gyel val� xor m�velet: UPPAAL-ban nincs ~oper�tor
			default:
				return "";
			}
		}
		// �sszead�s/kivon�s kifejez�s
		else if (expression instanceof NumericalAddSubtractExpression) {
			NumericalAddSubtractExpression addSubtract = (NumericalAddSubtractExpression) expression;
			return transformExpression(addSubtract.getLeftOperand()) + " " + addSubtract.getOperator().getLiteral() + transformExpression(addSubtract.getRightOperand());
		}
		// Szorz�s/oszt�s kifejez�s
		else if (expression instanceof NumericalMultiplyDivideExpression) {
			NumericalMultiplyDivideExpression multiplyDivide = (NumericalMultiplyDivideExpression) expression;
			return transformExpression(multiplyDivide.getLeftOperand()) + " " + multiplyDivide.getOperator().getLiteral() + transformExpression(multiplyDivide.getRightOperand());
		}
		// Bitshiftel� kifejez�s
		else if (expression instanceof ShiftExpression) {
			ShiftExpression shift = (ShiftExpression) expression;
			return transformExpression(shift.getLeftOperand()) + " " + shift.getOperator().getLiteral() + transformExpression(shift.getRightOperand());
		}
		// Bitenk�nti AND
		else if (expression instanceof BitwiseAndExpression) {
			BitwiseAndExpression and = (BitwiseAndExpression) expression;
			return transformExpression(and.getLeftOperand()) + " &amp; " + transformExpression(and.getRightOperand());
		}
		// Bitenk�nti OR
		else if (expression instanceof BitwiseOrExpression) {
			BitwiseOrExpression or = (BitwiseOrExpression) expression;
			return transformExpression(or.getLeftOperand()) + " | " + transformExpression(or.getRightOperand());
		}
		// Bitenk�nti XOR
		else if (expression instanceof BitwiseXorExpression) {
			BitwiseXorExpression xor = (BitwiseXorExpression) expression;
			return transformExpression(xor.getLeftOperand()) + " ^ " + transformExpression(xor.getRightOperand());
		}
		// Sima egyszer� �rt�kad�s eset�n le�rom az �rt�ket, majd visszaadom
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
			// M�s t�pus� v�ltoz�t m�g nem tudunk �tvinni UPPAALba
			else {
				throw new UnsupportedOperationException(PVExpression.getValue().getClass().toGenericString());
			}
		}
		// �rt�kad�s eset�n: Megn�zem a bal oldalt, kiteszek egy = jelet, majd megn�zem a jobb oldalt.
		else if (expression instanceof AssignmentExpression) {
			return transformExpression(((AssignmentExpression) expression).getVarRef()) + "  " + ((AssignmentExpression) expression).getOperator().getLiteral() + " " + transformExpression(((AssignmentExpression) expression).getExpression());
		}
		// Ha a kifejez�s egy FeatureCall (pl.: Server.value), akkor megn�zem a v�ltoz� nev�t
		// Interf�sz referenci�val nem foglalkozunk
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
			throw new UnsupportedOperationException("Nem ismert kifejez�s. :( " + expression.getClass().toGenericString());
		}
		// Ha a kifejez�s egy elemhivatkoz�s, megn�zem az elemet, �s ha v�ltoz�, visszaadom a nev�t
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
			throw new UnsupportedOperationException("Nem ismert kifejez�s. :( " + expression.getClass().toGenericString());
		}
		// Ha a kifejez�s egy logikai rel�ci�: Transzform�lom a baloldalt, ki�rom a rel�ci�s jelet, majd transzform�lom a jobboldalt
		else if (expression instanceof LogicalRelationExpression) {
			LogicalRelationExpression logicalRelation = (LogicalRelationExpression) expression;
			return transformExpression(logicalRelation.getLeftOperand()) + " " + logicalRelation.getOperator().getLiteral() + " " + transformExpression(logicalRelation.getRightOperand());
		}
		// Ha a kifejez�s egy logikai OR: Transzform�lom a baloldalt, ki�rom a rel�ci�s jelet, majd transzform�lom a jobboldalt
		else if (expression instanceof LogicalOrExpression) {
			LogicalOrExpression logicalOr = (LogicalOrExpression) expression;
			return transformExpression(logicalOr.getLeftOperand()) + " || " + transformExpression(logicalOr.getRightOperand()); 
		}
		// Ha a kifejez�s egy logikai OR: Transzform�lom a baloldalt, ki�rom a rel�ci�s jelet, majd transzform�lom a jobboldalt
		else if (expression instanceof LogicalAndExpression) {
			LogicalAndExpression logicalAnd = (LogicalAndExpression) expression;
			return transformExpression(logicalAnd.getLeftOperand()) + " &amp;&amp;" + transformExpression(logicalAnd.getRightOperand()); 
		}
		// Ha a kifejez�s egy logikai NOT: Ki�rom a ! jelet, majd transzform�lom az operandust
		else if (expression instanceof LogicalNotExpression) {
			LogicalNotExpression logicalNot = (LogicalNotExpression) expression;
			return " !" + transformExpression(logicalNot.getOperand());
		}
		// Ha egy z�r�jeles kifejez�s: kiteszek z�r�jeleket, �s a k�zt�k l�v� kifejez�st transzform�lom
		else if (expression instanceof ParenthesizedExpression) {
			ParenthesizedExpression parent = (ParenthesizedExpression) expression;
			return "(" + transformExpression(parent.getExpression()) + ")";
		}
		else if (expression instanceof EventRaisingExpression) {
			// Ekkor nem a transitionre �runk r�, hanem sync csatorn�t hozunk l�tre
			// lsd.: createRaisingEventSyncs() met�dus
			return "";
		}
		else if (expression instanceof EventValueReferenceExpression) {
			return transformExpression(((EventValueReferenceExpression) expression).getValue());
		}
		// K�l�nben nem ismert kifejez�s kiv�telt dobok
		else {
			throw new UnsupportedOperationException("Nem ismert kifejez�s. :( " + expression.getClass().toGenericString());
		}
	}

}
