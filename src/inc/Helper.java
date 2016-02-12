package inc;

import inc.util.CompositeStatesQuerySpecification;
import inc.util.EntryOfRegionsQuerySpecification;
import inc.util.EventsQuerySpecification;
import inc.util.FinalStatesQuerySpecification;
import inc.util.RegionsOfCompositeStatesQuerySpecification;
import inc.util.StatesQuerySpecification;
import inc.util.TopRegionsQuerySpecification;
import inc.util.VerticesOfRegionsQuerySpecification;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.incquery.runtime.api.IncQueryEngine;
import org.eclipse.incquery.runtime.api.impl.RunOnceQueryEngine;
import org.eclipse.incquery.runtime.exception.IncQueryException;
import org.yakindu.sct.model.sgraph.Choice;
import org.yakindu.sct.model.sgraph.Entry;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.State;
import org.yakindu.sct.model.sgraph.Statechart;
import org.yakindu.sct.model.sgraph.Transition;
import org.yakindu.sct.model.sgraph.Vertex;

/**
 * A Helper class with static methods.
 * While the model traverser actually build the Uppaal model, this class only provides information about the Yakindu model and its elements.
 * @author Graics Bence
 *
 */
public class Helper {

	private static IncQueryEngine engine;
	private static RunOnceQueryEngine runOnceEngine;
	
	/**
	 * Initialization.
	 * @param engine
	 * @param runOnceQueryEngine
	 * @throws IncQueryException 
	 */
	public static void setEngine(Resource resource) throws IncQueryException {
		engine = IncQueryEngine.on(resource);
		runOnceEngine = new RunOnceQueryEngine(resource);	
	}
	
	/**
	 * This method returns in parameter variable all the subregions and the subregions of the composite states of subregions recursively of the given state. 
	 * @param state Yakindu composite state whose all subregions are needed
	 * @param regionList Should be an empty list. This list will contain all the subregions at the end of the call.
	 * @throws IncQueryException 
	 */
	public static void addAllSubregionsToRegionList(State state, List<Region> regionList) throws IncQueryException {
		RegionsOfCompositeStatesMatcher compositeStateMatcher = engine.getMatcher(RegionsOfCompositeStatesQuerySpecification.instance());
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		for (RegionsOfCompositeStatesMatch regionsOfCompositeStateMatch : compositeStateMatcher.getAllMatches(state, null, null, null)) {
			regionList.add(regionsOfCompositeStateMatch.getSubregion());
			for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches(regionsOfCompositeStateMatch.getSubregion(), null)) {
				if (isCompositeState(verticesOfRegionsMatch.getVertex())) {
					addAllSubregionsToRegionList((State) verticesOfRegionsMatch.getVertex(), regionList);
				}
			}
		}
	}	
	
	/**
	 * Returns whether there is a deep history in or above the given region
	 * @param region Yakindu region
	 * @return
	 * @throws IncQueryException 
	 */
	private static boolean hasDeepHistoryAbove(Region region) throws IncQueryException {
		if (isTopRegion(region)) {
			return false;
		}
		else {
			RegionsOfCompositeStatesMatcher regionsOfCompositeStatesMatcher = engine.getMatcher(RegionsOfCompositeStatesQuerySpecification.instance());
			for (RegionsOfCompositeStatesMatch regionsOfCompositeStateMatch : regionsOfCompositeStatesMatcher.getAllMatches(null, null, null, region)) {
				return ((getEntryOfRegion(regionsOfCompositeStateMatch.getParentRegion()).getKind().getValue() == 2) || hasDeepHistoryAbove(regionsOfCompositeStateMatch.getParentRegion()));				
			}
			return false;
		}
	}
	
	/**
	 * This method returns whether the given region has history.
	 * @param region A Yakindu
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean hasHistory(Region region) throws IncQueryException {
		return (hasDeepHistoryAbove(region) || (getEntryOfRegion(region).getKind().getValue() == 1) || (getEntryOfRegion(region).getKind().getValue() == 2));
	}
	
	/**
	 * This method returns the entry node of the region.
	 * Assumes there is only one in each region (Otherwise the Yakindu model is not sound.)
	 * @param region Yakindu region
	 * @return Entry node
	 * @throws IncQueryException 
	 */
	public static Entry getEntryOfRegion(Region region) throws IncQueryException {
		EntryOfRegionsMatcher entryOfRegionsMatcher = engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		for (EntryOfRegionsMatch entryOfRegionsMatch : entryOfRegionsMatcher.getAllMatches(null, region, null, null, null)) {
			return entryOfRegionsMatch.getEntry();
		}
		return null;
	}
	
	/**
	 * This method returns whether the given vertex is a composite state.
	 * @param vertex Yakindu vertex
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean isCompositeState(Vertex vertex) throws IncQueryException {
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		for (CompositeStatesMatch compositeStatesMatch : compositeStatesMatcher.getAllMatches()) {
			if (vertex == compositeStatesMatch.getCompositeState()) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * This method returns whether the given vertex is a choice.
	 * @param vertex Yakindu vertex
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean isChoice(Vertex vertex) throws IncQueryException {
		return (vertex instanceof Choice);
	}
	
	/**
	 * This method returns whether the given vertex is an entry.
	 * @param vertex Yakindu vertex
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean isEntry(Vertex vertex) throws IncQueryException {
		return (vertex instanceof Entry);
	}
	
	/**
	 * This method returns whether the given region is top region.
	 * @param region Yakindu region
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean isTopRegion(Region region) throws IncQueryException {
		TopRegionsMatcher topRegionsMatcher = engine.getMatcher(TopRegionsQuerySpecification.instance());
		for (@SuppressWarnings("unused") TopRegionsMatch topRegionMatch : topRegionsMatcher.getAllMatches(region)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Returns the distance of the given vertex of the top region.
	 * (If the vertex is in the top region, the distance is 0.)
	 * @param vertex A Yakindu vertex whose level we want.
	 * @return An integer number as level.
	 * @throws IncQueryException 
	 */
	public static int getLevelOfVertex(Vertex vertex) throws IncQueryException {
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		RegionsOfCompositeStatesMatcher regionsOfCompositeStatesMatcher = engine.getMatcher(RegionsOfCompositeStatesQuerySpecification.instance());
		for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches(null, vertex)) {
			if (Helper.isTopRegion(verticesOfRegionsMatch.getRegion())) {
				return 0;
			}
			else {
				for (RegionsOfCompositeStatesMatch regionsOfCompositeStatesMatch : regionsOfCompositeStatesMatcher.getAllMatches(null, null, null, verticesOfRegionsMatch.getRegion())) {
					return (getLevelOfVertex(regionsOfCompositeStatesMatch.getCompositeState()) + 1);					
				}
			}
		}
		throw new IllegalArgumentException("A " + vertex.toString() + " composite-ja nem State és nem Statechart.");
	}
	
	/**
	 * Returns the region and the regions above it until the top region. (Recursion.)
	 * @param regionList Initially empty list that will contain all the regions at the end of the call.
	 * @param region The initial region
	 * @return A list of regions.
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
	 * This method returns whether the given vertex has entry event. (States can have entry events.)
	 * @param state Yakindu vertex
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean hasEntryEvent(Vertex state) throws IncQueryException {
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
			if (statesWithEntryEventMatch.getState() == state) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * This method returns whether the given vertex has exit event. (States can have exit events.)
	 * @param state Yakindu vertex
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean hasExitEvent(Vertex state) throws IncQueryException {
		for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch :runOnceEngine.getAllMatches(StatesWithExitEventWithoutOutgoingTransitionMatcher.querySpecification())) {
			if (statesWithExitEventMatch.getState() == state) {
				return true;				
			}
		}
		return false;
	}
	
	/**
	 * This method returns whether the Yakindu model has final state-
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean hasFinalState() throws IncQueryException {
		FinalStatesMatcher finalStatesMatcher = engine.getMatcher(FinalStatesQuerySpecification.instance());
		for (@SuppressWarnings("unused") FinalStatesMatch finalStatesMatch : finalStatesMatcher.getAllMatches()) {
			return true;
		}
		return false;
	}
	
	/**
	 * This method returns the target vertex of the entry's outgoing transition.
	 * @param entry The entry
	 * @return The target vertex
	 * @throws Exception
	 */
	public static Vertex getTargetOfEntry(Entry entry) throws Exception {
		for (Transition transition : entry.getOutgoingTransitions()) {
			return transition.getTarget();
		}
		throw new Exception("The entry has no outgoing transitions! Parent region: " + entry.getParentRegion().getName());
	}

	/**
	 * This method returns the template name of the addded region.
	 * @param region The Yakinu region whose template equivalent' s name is wanted
	 * @return The template equivalent name
	 * @throws IncQueryException
	 */
	public static String getTemplateNameFromRegionName(Region region) throws IncQueryException {
		if (isTopRegion(region)) {
			return (region.getName() + "OfStatechart").replaceAll(" ", "");			
		}
		else {
			return  (region.getName() + "Of" + ((State)region.getComposite()).getName()).replaceAll(" ", "");
		}
	}
	
	/**
	 * This method returns whether there is a Yakindu event with the added name.
	 * @param name Any string
	 * @return
	 * @throws IncQueryException
	 */
	public static boolean isEventName(String name) throws IncQueryException {
		EventsMatcher eventsMatcher = engine.getMatcher(EventsQuerySpecification.instance());
		for (@SuppressWarnings("unused") EventsMatch eventsMatch : eventsMatcher.getAllMatches(name)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Returns the name of the variable of the in event.
	 * @param eventName The in event name
	 * @return
	 * @throws IncQueryException
	 */
	public static String getInEventValueName(String eventName) throws IncQueryException {
		return eventName + "Value";
	}
	
	/**
	 * Returns an Uppaal expression that refers to the Uppaal equivalent of the Yakidu state:
	 * Process_"templateName"."stateName" 
	 * @param state
	 * @return
	 * @throws IncQueryException
	 */
	public static String getExpressionFromStateName(State state) throws IncQueryException {
		final String process = "Process_";		
		return process + getTemplateNameFromRegionName(state.getParentRegion()) + "." + state.getName();
	}

	/**
	 * Returns a collection of Uppaal location expression of all the states of  the Yakindu model.
	 * @return
	 * @throws IncQueryException
	 */
	public static Collection<String> getExpressionsOfLocations() throws IncQueryException {
		StatesMatcher statesMatcher = engine.getMatcher(StatesQuerySpecification.instance());
		Set<String> locationNames = new HashSet<String>();
		for (StatesMatch stateMatch : statesMatcher.getAllMatches()) {
			locationNames.add(getExpressionFromStateName(stateMatch.getState()));
		}
		return locationNames;
	}
	
}
