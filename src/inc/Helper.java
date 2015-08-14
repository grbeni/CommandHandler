package inc;

import java.util.List;

import org.eclipse.incquery.runtime.exception.IncQueryException;
import org.yakindu.sct.model.sgraph.Entry;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.State;
import org.yakindu.sct.model.sgraph.Statechart;
import org.yakindu.sct.model.sgraph.Vertex;

/**
 * Egy helper oszt�ly statikus met�dusokkal.
 * @author Graics Bence
 *
 */
public class Helper {

	private static PatternMatcher matcher = null;
	
	public static void setMatcher(PatternMatcher givenMatcher) {
		matcher = givenMatcher;
	}
	
	/**
	 * Ez a met�dus param�tersoron visszaadja a megadott state �sszes alatta l�v� region-j�t. (Nem csak az eggyel alatta l�v�ket.)
	 * @param state Yakindu composite state, amelynek az �sszes alatta l�v� regionje kell.
	 * @param regionList Ebbe a list�ba fogja betenni a met�dus a region�ket.
	 * @throws IncQueryException 
	 */
	public static void addAllSubregionsToRegionList(State state, List<Region> regionList) throws IncQueryException {
		for (RegionsOfCompositeStatesMatch regionsOfCompositeStateMatch : matcher.getAllRegionsOfCompositeStates()) {
			if (regionsOfCompositeStateMatch.getCompositeState() == state) {
				regionList.add(regionsOfCompositeStateMatch.getSubregion());
				for (VerticesOfRegionsMatch verticesOfRegionsMatch : matcher.getAllVerticesOfRegions()) {
					if (verticesOfRegionsMatch.getRegion() == regionsOfCompositeStateMatch.getSubregion() && (isCompositeState(verticesOfRegionsMatch.getVertex()))) {
						addAllSubregionsToRegionList((State) verticesOfRegionsMatch.getVertex(), regionList);
					}
				}
			}
		}
	}	
	
	/**
	 * Visszaadja, hogy tal�lhat�-e a regionben vagy felette deep history indicator.
	 * @param region Yakindu region, amely felett keress�k a deep history indicatort.
	 * @return Van-e a regionben, vagy felette deep history indicator. 
	 * @throws IncQueryException 
	 */
	private static boolean hasDeepHistoryAbove(Region region) throws IncQueryException {
		if (isTopRegion(region)) {
			return false;
		}
		else {
			for (RegionsOfCompositeStatesMatch regionsOfCompositeStateMatch : matcher.getAllRegionsOfCompositeStates()) {
				if (regionsOfCompositeStateMatch.getSubregion() == region) {
					return ((getEntryOfRegion(regionsOfCompositeStateMatch.getParentRegion()).getKind().getValue() == 2) || hasDeepHistoryAbove(regionsOfCompositeStateMatch.getParentRegion()));
				}
			}
			return false;
		}
	}
	
	/**
	 * Ez a met�dus megmondja, hogy a megadott regionnek, van-e historyja.
	 * @param region A Yakindu region, amelyr�l el szeretn�nk d�ntenti, hogy van-e historyja.
	 * @return Van-e a regionnek historyja.
	 * @throws IncQueryException
	 */
	public static boolean hasHistory(Region region) throws IncQueryException {
		return (hasDeepHistoryAbove(region) || (getEntryOfRegion(region).getKind().getValue() == 1) || (getEntryOfRegion(region).getKind().getValue() == 2));
	}
	
	/**
	 * Ez a met�dus visszaadja egy adott region entry elem�t.
	 * Felt�telezi, hogy csak egy ilyen van egy region-ben. (K�l�nben a Yakindu modell hib�s.)
	 * @param region A Yakindu region, amelyben keress�k az entry.
	 * @return A Yakindu entry elem.
	 * @throws IncQueryException 
	 */
	public static Entry getEntryOfRegion(Region region) throws IncQueryException {
		for (EntryOfRegionsMatch entryOfRegionsMatch : matcher.getAllRegionsWithEntry()) {
			if (entryOfRegionsMatch.getRegion() == region) {
				return entryOfRegionsMatch.getEntry();
			}
		}
		return null;
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy a megadott Yakindu vertex composite state-e.
	 * @param vertex Yakindu vertex, amelyr�l el szeretn�nk d�nteni, hogy composite state-e.
	 * @return Composite state-e vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean isCompositeState(Vertex vertex) throws IncQueryException {
		for (CompositeStatesMatch compositeStatesMatch : matcher.getAllCompositeStates()) {
			if (compositeStatesMatch.getCompositeState() == vertex) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy a megadott Yakindu vertex choice-e.
	 * @param vertex Yakindu vertex, amelyr�l el szeretn�nk d�nteni, hogy choice-e.
	 * @return Choice-e vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean isChoice(Vertex vertex) throws IncQueryException {
		for (ChoicesMatch choicesMatch : matcher.getAllChoices()) {
			if (choicesMatch.getChoice() == vertex) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy a megadott Yakindu region legfels� szint�-e.
	 * @param region Yakindu region, amelyr�l el szeretn�nk d�nteni, hogy top szint�-e.
	 * @return Top szint�-e vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean isTopRegion(Region region) throws IncQueryException {
		for (TopRegionsMatch topRegionMatch : matcher.getAllTopRegions()) {
			if (topRegionMatch.getRegion() == region) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Visszaadja, hogy egy vertex elem milyen messze tal�lhat� a legf�ls� regiont�l.
	 * (Ha a legf�ls� regionben tal�lhat�, akkor ez az �rt�k 0.)
	 * @param vertex A Yakindu vertex, amelynek lek�rj�k a szintj�t.
	 * @return A szint mint eg�sz sz�m.
	 * @throws IncQueryException 
	 */
	public static int getLevelOfVertex(Vertex vertex) throws IncQueryException {
		for (VerticesOfRegionsMatch verticesOfRegionsMatch : matcher.getAllVerticesOfRegions()) {
			if (verticesOfRegionsMatch.getVertex() == vertex) {
				if (Helper.isTopRegion(verticesOfRegionsMatch.getRegion())) {
					return 0;
				}
				else {
					for (RegionsOfCompositeStatesMatch regionsOfCompositeStatesMatch : matcher.getAllRegionsOfCompositeStates()) {
						if (regionsOfCompositeStatesMatch.getSubregion() == verticesOfRegionsMatch.getRegion()) {
							return (getLevelOfVertex(regionsOfCompositeStatesMatch.getCompositeState()) + 1);
						}
					}
				}
			}
		}
		throw new IllegalArgumentException("A " + vertex.toString() + " composite-ja nem State �s nem Statechart.");
	}
	
	/**
	 * Visszaadja a regiont, �s a felette l�v� faszerkezetben elhelyezked� region�ket.
	 * @param regionList Eleinte �res lista, amelyben t�rolni fogja a region�ket.
	 * @param region Ett�l a Yakindu region-t�l kezde indulunk el felfel�, �s t�roljuk el a region�ket.
	 * @return Egy lista a faszerkezetben l�v� region�kr�l
	 */
	public static List<Region> getThisAndUpperRegions(List<Region> regionList, Region region) {
		regionList.add(region);
		if (region.getComposite() instanceof Statechart) {
			return regionList;
		}
		else {		
			return getThisAndUpperRegions(regionList, ((State) region.getComposite()).getParentRegion());
		}
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy a megadott Yakindu vertexnek van-e entry eventje.
	 * @param state Yakindu vertex, amelyr�l el szeretn�nk d�nteni, van-e entry eventje.
	 * @return Van-e entry eventje vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean hasEntryEvent(Vertex state) throws IncQueryException {
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : matcher.getAllStatesWithEntryEvent()) {
			if (statesWithEntryEventMatch.getState() == state) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy a megadott Yakindu vertexnek van-e exit eventje.
	 * @param state Yakindu vertex, amelyr�l el szeretn�nk d�nteni, van-e exit eventje.
	 * @return Van-e exit eventje vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean hasExitEvent(Vertex state) throws IncQueryException {
		for (StatesWithExitEventMatch statesWithExitEventMatch : matcher.getAllStatesWithExitEvent()) {
			if (statesWithExitEventMatch.getState() == state) {
				return true;
			}
		}
		return false;
	}
	
}
