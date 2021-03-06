package inc;

import inc.util.StatesWithEntryEventQuerySpecification;

import java.util.Collection;

import org.eclipse.incquery.runtime.api.AdvancedIncQueryEngine;
import org.eclipse.incquery.runtime.api.IPatternMatch;
import org.eclipse.incquery.runtime.api.IQuerySpecification;
import org.eclipse.incquery.runtime.api.IncQueryEngine;
import org.eclipse.incquery.runtime.api.IncQueryMatcher;
import org.eclipse.incquery.runtime.api.impl.RunOnceQueryEngine;
import org.eclipse.incquery.runtime.exception.IncQueryException;
import org.yakindu.sct.model.sgraph.Entry;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.Statechart;

import de.uni_paderborn.uppaal.templates.Location;

public class PatternMatcher {

	private static org.eclipse.emf.ecore.resource.Resource resource = null;

	private static IncQueryEngine engine = null;
	
	private static RunOnceQueryEngine runOnceEngine = null;

	private AdvancedIncQueryEngine from;

	@SuppressWarnings("deprecation")
	public void setResource(org.eclipse.emf.ecore.resource.Resource res) {
		try {
			resource = res;
			engine = IncQueryEngine.on(resource);
	
			
			runOnceEngine = new RunOnceQueryEngine(res);
			runOnceEngine.getAllMatches(StatesWithEntryEventQuerySpecification.instance());
		} catch (IncQueryException e) {
			e.printStackTrace();
		}
	}
	
	// �gy k�ne tal�n fel�p�teni
	// Beni mutatta
	
	public SatechartToStateNameMatch getStateName(Statechart chart)
			throws IncQueryException {
		Collection<SatechartToStateNameMatch> allMatches = SatechartToStateNameMatcher
				.on(engine).getAllMatches();
		for (SatechartToStateNameMatch match : allMatches) {
			if (match.getStateChart() == chart) {
				return match;
			}
		}
		return null;
	}

	public String getEntryName() throws IncQueryException {
		Collection<GetEntryMatch> matches = GetEntryMatcher.on(engine).getAllMatches();
		for (GetEntryMatch match : matches) {
			return match.getName();
		}
		return null;
	}
	
	public Entry getEntry(Region region) throws IncQueryException {
		Collection<GetEntryMatch> matches = GetEntryMatcher.on(engine).getAllMatches();
		for (GetEntryMatch match : matches) {
			if (match.getRegion() == region) {
				return match.getEntry();
			}
		}
		return null;
	}

	
	public SourceAndTargetOfTransitionsMatch getTransition(Location sourceLocation, Location targetLocation)
			throws IncQueryException {
		Collection<SourceAndTargetOfTransitionsMatch> allMatches = SourceAndTargetOfTransitionsMatcher
				.on(engine).getAllMatches();
		for (SourceAndTargetOfTransitionsMatch match : allMatches) {
			if (match.getSource() == sourceLocation && match.getTarget() == targetLocation) {
				return match;
			}
		}
		return null;
	}
//
	
//	public Collection<SourceAndTargetOfTransitionsMatch> getAllTransitions() throws IncQueryException {
////		Collection<SourceAndTargetOfTransitionsMatch> allMatches = SourceAndTargetOfTransitionsMatcher.on(engine).getAllMatches();
////		SourceAndTargetOfTransitionsMatcher matcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
////		Set<Transition> transitions = Sets.newHashSet();
////		
////		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : allMatches) {
////			transitions.add(sourceAndTargetOfTransitionsMatch.getTransition());
////		}
//		return SourceAndTargetOfTransitionsMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EntryOfRegionsMatch> getAllRegionsWithEntry() throws IncQueryException {
//		return EntryOfRegionsMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<TopRegionsMatch> getAllTopRegions() throws IncQueryException {
//		return TopRegionsMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<ChoicesMatch> getAllChoices() throws IncQueryException {
//		return ChoicesMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<StatesMatch> getAllStates() throws IncQueryException {
//		return StatesMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<VariableDefinitionsMatch> getAllVariables() throws IncQueryException {
//		return VariableDefinitionsMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<FinalStatesMatch> getAllFinalStates() throws IncQueryException {
//		return FinalStatesMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<FinalStateEdgeMatch> getAllFinalStateEdges() throws IncQueryException {
//		return FinalStateEdgeMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<ExitNodesMatch> getAllExitNodes() throws IncQueryException {
//		return ExitNodesMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesInSameRegionMatch> getAllEdgesInSameRegion() throws IncQueryException {
//		return EdgesInSameRegionMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesAcrossRegionsMatch> getAllEdgesAcrossRegions() throws IncQueryException {
//		return EdgesAcrossRegionsMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<CompositeStatesMatch> getAllCompositeStates() throws IncQueryException {
//		return CompositeStatesMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<RegionsOfCompositeStatesMatch> getAllRegionsOfCompositeStates() throws IncQueryException {
//		return RegionsOfCompositeStatesMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<VerticesOfRegionsMatch> getAllVerticesOfRegions() throws IncQueryException {
//		return VerticesOfRegionsMatcher.on(engine).getAllMatches();
//	} 
//	
//	public Collection<EdgesWithEffectMatch> getAllEdgesWithEffect() throws IncQueryException {
//		return EdgesWithEffectMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesWithGuardMatch> getAllEdgesWithGuard() throws IncQueryException {
//		return EdgesWithGuardMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesWithRaisingEventMatch> getAllEdgesWithRaisingEvent() throws IncQueryException {
//		return EdgesWithRaisingEventMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesWithTriggerElementReferenceMatch> getAllEdgesWithTriggerElementReference() throws IncQueryException {
//		return EdgesWithTriggerElementReferenceMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesFromEntryOfParallelRegionsMatch> getEdgesFromEntryOfParallelRegions() throws IncQueryException {
//		return EdgesFromEntryOfParallelRegionsMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<EdgesWithTimeTriggerMatch> getEdgesWithTimeTrigger() throws IncQueryException {
//		return EdgesWithTimeTriggerMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<TriggerOfTransitionMatch> getAllTriggersOfTransitions() throws IncQueryException {
//		return TriggerOfTransitionMatcher.on(engine).getAllMatches();
//	}
//	
//	public Collection<ExitNodeSyncMatch> getAllExitNodeSyncs() throws IncQueryException {
//		return ExitNodeSyncMatcher.on(engine).getAllMatches();
//	}
	
//	public Collection<? extends IPatternMatch> getAllMatchesFor(
//			IQuerySpecification<IncQueryMatcher<? extends IPatternMatch>> query)
//			throws IncQueryException {
//		return engine.getMatcher(query).getAllMatches();
//	}
//
//	public Collection<StatesWithEntryEventMatch> getAllStatesWithEntryEvent()
//			throws IncQueryException {
//		// return StatesWithEntryEventMatcher.on(engine).getAllMatches();
//		return runOnceEngine.getAllMatches(StatesWithEntryEventMatcher
//				.querySpecification());
//	}
//
//	public Collection<StatesWithExitEventMatch> getAllStatesWithExitEvent()
//			throws IncQueryException {
//		// return StatesWithExitEventMatcher.on(engine).getAllMatches();
//		return runOnceEngine.getAllMatches(StatesWithExitEventMatcher
//				.querySpecification());
//	}
//
//	public Collection<StatesWithExitEventWithoutOutgoingTransitionMatch> getAllStatesWithExitEventWithoutOutgoing()
//			throws IncQueryException {
//		// return StatesWithExitEventMatcher.on(engine).getAllMatches();
//		return runOnceEngine
//				.getAllMatches(StatesWithExitEventWithoutOutgoingTransitionMatcher
//						.querySpecification());
//	}
//
//	public int getCompositeStateCount() throws IncQueryException {
//		// return StatesWithExitEventMatcher.on(engine).getAllMatches();
//		return CompositeStateCountMatcher.on(engine).countMatches();
//	}
	
}
