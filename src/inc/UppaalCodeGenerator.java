package inc;

import org.eclipse.emf.common.util.EList;
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
import org.yakindu.sct.model.stext.stext.Guard;
import org.yakindu.sct.model.stext.stext.ReactionEffect;
import org.yakindu.sct.model.stext.stext.ReactionTrigger;
import org.yakindu.sct.model.stext.stext.VariableDefinition;

public class UppaalCodeGenerator {

	/**
	 * A v�ltoz� definici�kat l�trehoz� met�dus.
	 * 
	 * @param variable Yakindu VariableDefinition, amely v�ltoz� definici�t tartalmaz.
	 * @return Egy string, amely tartalmazza a v�ltoz� definici�j�t.
	 */
	public static String transformVariable(VariableDefinition variable) {
		// Le�rom a v�ltoz� t�pus�t �s nev�t
		if (variable == null) {
			return "";
		}
		String expression = "";
		if (variable.isConst()) {
			expression = "const " + expression;
		}
		if (variable.getType().getName().equals("boolean")) {
			expression = "bool";
		} else if (variable.getType().getName().equals("integer")) {
			expression = "int";
		}
		expression = expression + " " + variable.getName();

		// Ha nincsen inicializ�ci�, visszaadom a deklar�ci�t
		if (variable.getInitialValue() == null) {
			return expression + ";";
		}
		// K�l�nben hozz�teszek m�g egy �rt�kad� oper�tort
		else {
			expression = expression + " =";
		}
		// Stringg� transzform�lom a kifejez�st, majd visszaadom
		expression = expression
				+ transformExpression(variable.getInitialValue());
		return expression + ";";
	}
	
	/**
	 * A Yakindu ReactionEffecteket UPPAAL szab�lyoknak megfelel� string effectt� transzform�l� met�dus.
	 * @param reaction A Yakindu ReactionEffect, amelyet transzform�lni kell.
	 * @return Egy string, amely tartalmazza az effecteket, UPPAAL szab�lyoknak megfelel�en.
	 */
	public static String transformEffect(ReactionEffect reaction) {
		if (reaction == null) {
			return "";
		}		
		String expression = "";
		EList<Expression> expressionList = reaction.getActions();
		// Vizsg�lni kell, hogy ez null-e?
		for (Expression exp : expressionList) {
			if (!expression.equals("")) {
				expression = expression + ", ";
			}
			expression = expression + transformExpression(exp);
		}
		return expression;		
	}
	
	/**
	 * A Yakindu ReactionTriggereket UPPAAL szab�lyoknak megfelel� guardd� transzform�l� met�dus. Csak a guardokkal foglalkozik, triggerekkel nem.
	 * @param trigger A Yakindu ReactionTrigger, amleyet transzform�lni kell.
	 * @return Egy string, amely tartalmazza a guardot, UPPAAL szab�lyoknak megfelel�en.
	 */
	public static String transformGuard(ReactionTrigger trigger) {
		if (trigger == null) {
			return "";
		}
		String expression = "";
		if (trigger.getGuard() != null) {
			Guard guard = trigger.getGuard();
			expression = expression + transformExpression(guard.getExpression());
		}
		return expression;
	}

	/**
	 * A Yakindu expression�ket UPPAAL szab�lyoknak megfelel� kifejez�sekk� transzform�l� met�dus.
	 * @param expression A Yakindu expression, amelyet transzform�lni kell.
	 * @return Egy string, amely a transzform�lt kifejez�st tartalmazza, UPPAAL szab�lyoknak megfelel�en.
	 */
	private static String transformExpression(Expression expression) {
		// Ha a kifejez�s egy egyoperandus� numerikus kifejez�ssel kezd�dik, akkor le�rom
		// az oper�tort, majd letranszform�lom a marad�k kifejez�st is.
		// Visszaadom, amit kaptam.
		if (expression instanceof NumericalUnaryExpression) {
			NumericalUnaryExpression NUExpression = (NumericalUnaryExpression) expression;
			switch (NUExpression.getOperator().getValue()) {
			case 0:
				return " +" + transformExpression(NUExpression.getOperand());
			case 1:
				return " -" + transformExpression(NUExpression.getOperand());
			case 2:
				return " ^" + transformExpression(NUExpression.getOperand());
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
			return transformExpression(((AssignmentExpression) expression).getVarRef()) + " = " + transformExpression(((AssignmentExpression) expression).getExpression());
		}
		// Ha a kifejez�s egy FeatureCall (pl.: Server.value), akkor megn�zem a v�ltoz� nev�t
		// Interf�sz referenci�val nem foglalkozunk
		else if (expression instanceof FeatureCall) {
			FeatureCall featureCall = (FeatureCall) expression;
			if (featureCall.getFeature() instanceof VariableDefinition) {
				VariableDefinition variable = (VariableDefinition) featureCall.getFeature();
				return variable.getName();
			}
			return "Az FeatureCall feature-e nem VariableDefinition. :(";
		}
		// Ha a kifejez�s egy elemhivatkoz�s, megn�zem az elemet, �s ha v�ltoz�, visszaadom a nev�t
		else if (expression instanceof ElementReferenceExpression) {
			ElementReferenceExpression elementReference = (ElementReferenceExpression) expression;
			if (elementReference.getReference() instanceof VariableDefinition) {
				VariableDefinition variable = (VariableDefinition) elementReference.getReference();
				return variable.getName();
			}
			return "Az ElementReferenceExpression reference-e nem VariableDefinition. :(";
		}
		// Ha a kifejez�s egy logikai rel�ci�: Transzform�lom a baloldalt, ki�rom a rel�ci�s jelet, majd transzform�lom a jobboldalt
		else if (expression instanceof LogicalRelationExpression) {
			LogicalRelationExpression logicalRelation = (LogicalRelationExpression) expression;
			return transformExpression(logicalRelation.getLeftOperand()) + " " + logicalRelation.getOperator().getLiteral() + transformExpression(logicalRelation.getRightOperand());
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
		// K�l�nben nem ismert kifejez�s kiv�telt dobok
		else {
			throw new UnsupportedOperationException("Nem ismert kifejez�s. :( " + expression.getClass().toGenericString());
		}
	}

}