package inc;

import inc.util.ChoicesQuerySpecification;
import inc.util.CompositeStatesQuerySpecification;
import inc.util.EntryOfRegionsQuerySpecification;
import inc.util.FinalStatesQuerySpecification;
import inc.util.RegionsOfCompositeStatesQuerySpecification;
import inc.util.StatesQuerySpecification;
import inc.util.StatesWithEntryEventQuerySpecification;
import inc.util.StatesWithExitEventWithoutOutgoingTransitionQuerySpecification;
import inc.util.TopRegionsQuerySpecification;
import inc.util.VerticesOfRegionsQuerySpecification;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.incquery.runtime.api.IncQueryEngine;
import org.eclipse.incquery.runtime.api.impl.RunOnceQueryEngine;
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

	private static IncQueryEngine engine;
	private static RunOnceQueryEngine runOnceQueryEngine;
	
	public static void setEngine(IncQueryEngine engine, RunOnceQueryEngine runOnceQueryEngine) {
		Helper.engine = engine;
		Helper.runOnceQueryEngine = runOnceQueryEngine;		
	}
	
	/**
	 * Ez a met�dus param�tersoron visszaadja a megadott state �sszes alatta l�v� region-j�t. (Nem csak az eggyel alatta l�v�ket.)
	 * @param state Yakindu composite state, amelynek az �sszes alatta l�v� regionje kell.
	 * @param regionList Ebbe a list�ba fogja betenni a met�dus a region�ket.
	 * @throws IncQueryException 
	 */
	public static void addAllSubregionsToRegionList(State state, List<Region> regionList) throws IncQueryException {
		RegionsOfCompositeStatesMatcher compositeStateMatcher = engine.getMatcher(RegionsOfCompositeStatesQuerySpecification.instance());
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		for (RegionsOfCompositeStatesMatch regionsOfCompositeStateMatch : compositeStateMatcher.getAllMatches()) {
			if (regionsOfCompositeStateMatch.getCompositeState() == state) {
				regionList.add(regionsOfCompositeStateMatch.getSubregion());
				for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches()) {
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
			RegionsOfCompositeStatesMatcher regionsOfCompositeStatesMatcher = engine.getMatcher(RegionsOfCompositeStatesQuerySpecification.instance());
			for (RegionsOfCompositeStatesMatch regionsOfCompositeStateMatch : regionsOfCompositeStatesMatcher.getAllMatches()) {
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
		EntryOfRegionsMatcher entryOfRegionsMatcher = engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		for (EntryOfRegionsMatch entryOfRegionsMatch : entryOfRegionsMatcher.getAllMatches()) {
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
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		for (CompositeStatesMatch compositeStatesMatch : compositeStatesMatcher.getAllMatches()) {
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
		ChoicesMatcher choicesMatcher = engine.getMatcher(ChoicesQuerySpecification.instance());
		for (ChoicesMatch choicesMatch : choicesMatcher.getAllMatches()) {
			if (choicesMatch.getChoice() == vertex) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy a megadott Yakindu vertex entry-e.
	 * @param vertex Yakindu vertex, amelyr�l el szeretn�nk d�nteni, hogy entry-e.
	 * @return Entry-e vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean isEntry(Vertex vertex) throws IncQueryException {
		EntryOfRegionsMatcher entryOfRegionsMatcher = engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		for (EntryOfRegionsMatch entryMatch : entryOfRegionsMatcher.getAllMatches()) {
			if (entryMatch.getEntry() == vertex) {
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
		TopRegionsMatcher topRegionsMatcher = engine.getMatcher(TopRegionsQuerySpecification.instance());
		for (TopRegionsMatch topRegionMatch : topRegionsMatcher.getAllMatches()) {
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
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		RegionsOfCompositeStatesMatcher regionsOfCompositeStatesMatcher = engine.getMatcher(RegionsOfCompositeStatesQuerySpecification.instance());
		for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches()) {
			if (verticesOfRegionsMatch.getVertex() == vertex) {
				if (Helper.isTopRegion(verticesOfRegionsMatch.getRegion())) {
					return 0;
				}
				else {
					for (RegionsOfCompositeStatesMatch regionsOfCompositeStatesMatch : regionsOfCompositeStatesMatcher.getAllMatches()) {
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
		StatesWithEntryEventMatcher statesWithEntryEventMatcher = engine.getMatcher(StatesWithEntryEventQuerySpecification.instance());
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : statesWithEntryEventMatcher.getAllMatches()) {
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
		StatesWithExitEventWithoutOutgoingTransitionMatcher statesWithExitEventWithoutOutgoingTransitionMatcher = 
				engine.getMatcher(StatesWithExitEventWithoutOutgoingTransitionQuerySpecification.instance());
		for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : 
			statesWithExitEventWithoutOutgoingTransitionMatcher.getAllMatches()) {
			if (statesWithExitEventMatch.getState() == state) {
				return true;				
			}
		}
		return false;
	}
	
	/**
	 * Ez a met�dus eld�nti, hogy van-e final state a Yakindu modellben.
	 * @return Tal�lt-e final state-et vagy nem.
	 * @throws IncQueryException
	 */
	public static boolean hasFinalState() throws IncQueryException {
		FinalStatesMatcher finalStatesMatcher = engine.getMatcher(FinalStatesQuerySpecification.instance());
		for (@SuppressWarnings("unused") FinalStatesMatch finalStatesMatch : finalStatesMatcher.getAllMatches()) {
			return true;
		}
		return false;
	}

	public static String getTemplateNameFromRegionName(Region region) throws IncQueryException {
		if (isTopRegion(region)) {
			return (region.getName() + "OfStatechart").replaceAll(" ", "");			
		}
		else {
			return  (region.getName() + "Of" + ((State)region.getComposite()).getName()).replaceAll(" ", "");
		}
	}
	
	public static String getInEventValueName(String eventName) throws IncQueryException {
		return eventName + "Value";
	}
	
	public static String getExpressionFromStateName(State state) throws IncQueryException {
		final String process = "Process_";		
		return process + getTemplateNameFromRegionName(state.getParentRegion()) + "." + state.getName();
	}

	public static Collection<String> getNamesOfLocations() throws IncQueryException {
		StatesMatcher statesMatcher = engine.getMatcher(StatesQuerySpecification.instance());
		Set<String> locationNames = new HashSet<String>();
		for (StatesMatch stateMatch : statesMatcher.getAllMatches()) {
			locationNames.add(getExpressionFromStateName(stateMatch.getState()));
		}
		return locationNames;
	}
	
}
