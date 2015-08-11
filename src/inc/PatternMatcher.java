package inc;

import java.util.Collection;

import org.eclipse.incquery.runtime.api.IncQueryEngine;
import org.eclipse.incquery.runtime.exception.IncQueryException;
import org.yakindu.sct.model.sgraph.Entry;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.Statechart;

import de.uni_paderborn.uppaal.templates.Location;

public class PatternMatcher {

	private static IncQueryEngine engine = null;

	private static org.eclipse.emf.ecore.resource.Resource resource = null;

	public void setResource(org.eclipse.emf.ecore.resource.Resource res) {
		resource = res;
		try {
			engine = IncQueryEngine.on(res);
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
	
	public Collection<SourceAndTargetOfTransitionsMatch> getAllTransitions() throws IncQueryException {
		return SourceAndTargetOfTransitionsMatcher.on(engine).getAllMatches();
	}
	
	public Collection<EntryOfRegionsMatch> getAllRegionsWithEntry() throws IncQueryException {
		return EntryOfRegionsMatcher.on(engine).getAllMatches();
	}
	
	public Collection<TopRegionsMatch> getAllTopRegions() throws IncQueryException {
		return TopRegionsMatcher.on(engine).getAllMatches();
	}
	
	public Collection<ChoicesMatch> getAllChoices() throws IncQueryException {
		return ChoicesMatcher.on(engine).getAllMatches();
	}
	
	public Collection<StatesMatch> getAllStates() throws IncQueryException {
		return StatesMatcher.on(engine).getAllMatches();
	}
	
	public Collection<VariableDefinitionsMatch> getAllVariables() throws IncQueryException {
		return VariableDefinitionsMatcher.on(engine).getAllMatches();
	}
	
	public Collection<FinalStatesMatch> getAllFinalStates() throws IncQueryException {
		return FinalStatesMatcher.on(engine).getAllMatches();
	}
	
	public Collection<ExitNodesMatch> getAllExitNodes() throws IncQueryException {
		return ExitNodesMatcher.on(engine).getAllMatches();
	}
	
	public Collection<EdgesInSameRegionMatch> getAllEdgesInSameRegion() throws IncQueryException {
		return EdgesInSameRegionMatcher.on(engine).getAllMatches();
	}
	
	public Collection<EdgesAcrossRegionsMatch> getAllEdgesAcrossRegions() throws IncQueryException {
		return EdgesAcrossRegionsMatcher.on(engine).getAllMatches();
	}
	
	public Collection<CompositeStatesMatch> getAllCompositeStates() throws IncQueryException {
		return CompositeStatesMatcher.on(engine).getAllMatches();
	}
	
	public Collection<RegionsOfCompositeStatesMatch> getAllRegionsOfCompositeStates() throws IncQueryException {
		return RegionsOfCompositeStatesMatcher.on(engine).getAllMatches();
	}
	
	public Collection<VerticesOfRegionsMatch> getAllVerticesOfRegions() throws IncQueryException {
		return VerticesOfRegionsMatcher.on(engine).getAllMatches();
	} 
	
	public Collection<EdgesWithEffectMatch> getAllEdgesWithEffect() throws IncQueryException {
		return EdgesWithEffectMatcher.on(engine).getAllMatches();
	}
	
	public Collection<EdgesWithGuardMatch> getAllEdgesWithGuard() throws IncQueryException {
		return EdgesWithGuardMatcher.on(engine).getAllMatches();
	}
	
	public Collection<StatesWithEntryEventMatch> getAllStatesWithEntryEvent() throws IncQueryException {
		return StatesWithEntryEventMatcher.on(engine).getAllMatches();
	}
	
	public Collection<StatesWithExitEventMatch> getAllStatesWithExitEvent() throws IncQueryException {
		return StatesWithExitEventMatcher.on(engine).getAllMatches();
	}
	
}
