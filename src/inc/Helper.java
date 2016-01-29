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
 * Egy helper osztály statikus metódusokkal.
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
	 * Ez a metódus paramétersoron visszaadja a megadott state összes alatta lévõ region-jét. (Nem csak az eggyel alatta lévõket.)
	 * @param state Yakindu composite state, amelynek az összes alatta lévõ regionje kell.
	 * @param regionList Ebbe a listába fogja betenni a metódus a regionöket.
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
	 * Visszaadja, hogy található-e a regionben vagy felette deep history indicator.
	 * @param region Yakindu region, amely felett keressük a deep history indicatort.
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
	 * Ez a metódus megmondja, hogy a megadott regionnek, van-e historyja.
	 * @param region A Yakindu region, amelyrõl el szeretnénk döntenti, hogy van-e historyja.
	 * @return Van-e a regionnek historyja.
	 * @throws IncQueryException
	 */
	public static boolean hasHistory(Region region) throws IncQueryException {
		return (hasDeepHistoryAbove(region) || (getEntryOfRegion(region).getKind().getValue() == 1) || (getEntryOfRegion(region).getKind().getValue() == 2));
	}
	
	/**
	 * Ez a metódus visszaadja egy adott region entry elemét.
	 * Feltételezi, hogy csak egy ilyen van egy region-ben. (Különben a Yakindu modell hibás.)
	 * @param region A Yakindu region, amelyben keressük az entry.
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
	 * Ez a metódus eldönti, hogy a megadott Yakindu vertex composite state-e.
	 * @param vertex Yakindu vertex, amelyrõl el szeretnénk dönteni, hogy composite state-e.
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
	 * Ez a metódus eldönti, hogy a megadott Yakindu vertex choice-e.
	 * @param vertex Yakindu vertex, amelyrõl el szeretnénk dönteni, hogy choice-e.
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
	 * Ez a metódus eldönti, hogy a megadott Yakindu vertex entry-e.
	 * @param vertex Yakindu vertex, amelyrõl el szeretnénk dönteni, hogy entry-e.
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
	 * Ez a metódus eldönti, hogy a megadott Yakindu region legfelsõ szintû-e.
	 * @param region Yakindu region, amelyrõl el szeretnénk dönteni, hogy top szintû-e.
	 * @return Top szintû-e vagy nem.
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
	 * Visszaadja, hogy egy vertex elem milyen messze található a legfölsõ regiontõl.
	 * (Ha a legfölsõ regionben található, akkor ez az érték 0.)
	 * @param vertex A Yakindu vertex, amelynek lekérjük a szintjét.
	 * @return A szint mint egész szám.
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
		throw new IllegalArgumentException("A " + vertex.toString() + " composite-ja nem State és nem Statechart.");
	}
	
	/**
	 * Visszaadja a regiont, és a felette lévõ faszerkezetben elhelyezkedõ regionöket.
	 * @param regionList Eleinte üres lista, amelyben tárolni fogja a regionöket.
	 * @param region Ettõl a Yakindu region-tõl kezde indulunk el felfelé, és tároljuk el a regionöket.
	 * @return Egy lista a faszerkezetben lévõ regionökrõl
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
	 * Ez a metódus eldönti, hogy a megadott Yakindu vertexnek van-e entry eventje.
	 * @param state Yakindu vertex, amelyrõl el szeretnénk dönteni, van-e entry eventje.
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
	 * Ez a metódus eldönti, hogy a megadott Yakindu vertexnek van-e exit eventje.
	 * @param state Yakindu vertex, amelyrõl el szeretnénk dönteni, van-e exit eventje.
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
	 * Ez a metódus eldönti, hogy van-e final state a Yakindu modellben.
	 * @return Talált-e final state-et vagy nem.
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
