package inc

import de.uni_paderborn.uppaal.templates.Edge
import de.uni_paderborn.uppaal.templates.Location
import de.uni_paderborn.uppaal.templates.Synchronization
import de.uni_paderborn.uppaal.templates.Template
import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelSaver
import hu.bme.mit.inf.alf.uppaal.transformation.serialization.UppaalModelSerializer
import inc.util.ChoicesQuerySpecification
import inc.util.CompositeStatesQuerySpecification
import inc.util.EdgesAcrossRegionsQuerySpecification
import inc.util.EdgesInSameRegionQuerySpecification
import inc.util.EdgesWithEffectQuerySpecification
import inc.util.EdgesWithGuardQuerySpecification
import inc.util.EdgesWithRaisingEventQuerySpecification
import inc.util.EdgesWithTimeTriggerQuerySpecification
import inc.util.EntryOfRegionsQuerySpecification
import inc.util.EventsQuerySpecification
import inc.util.EventsWithTypeQuerySpecification
import inc.util.ExitNodeSyncQuerySpecification
import inc.util.ExitNodesQuerySpecification
import inc.util.FinalStateEdgeQuerySpecification
import inc.util.FinalStatesQuerySpecification
import inc.util.InEventValuesQuerySpecification
import inc.util.InEventsQuerySpecification
import inc.util.InValuesQuerySpecification
import inc.util.LocalReactionValueOfEffectQuerySpecification
import inc.util.RaisingExpressionsWithAssignmentQuerySpecification
import inc.util.SourceAndTargetOfTransitionsQuerySpecification
import inc.util.StatesQuerySpecification
import inc.util.TriggerOfTransitionQuerySpecification
import inc.util.VariableDefinitionsQuerySpecification
import inc.util.VerticesOfRegionsQuerySpecification
import java.util.ArrayList
import java.util.HashSet
import java.util.List
import org.eclipse.emf.ecore.resource.Resource
import org.eclipse.incquery.runtime.exception.IncQueryException
import org.yakindu.base.expressions.expressions.Expression
import org.yakindu.sct.model.sgraph.Region
import org.yakindu.sct.model.sgraph.State
import org.yakindu.sct.model.sgraph.Transition
import org.yakindu.sct.model.sgraph.Vertex
import org.yakindu.sct.model.stext.stext.EventRaisingExpression
import org.yakindu.sct.model.stext.stext.LocalReaction

import static inc.Helper.*
import de.uni_paderborn.uppaal.templates.SynchronizationKind

class YakinduToUppaalTransformer {
	
	def run(Resource resource, String fileURISubstring) {
		var trace = new Trace(resource)
		Helper.engine = resource
								
		try {
			// We only add end variable if there is a final state in the Yakindu model
			if (Helper.hasFinalState()) {
				trace.builder.addGlobalDeclaration("bool " + trace.endVar + " = false;" );
			}
				// Creation of integer and boolean variables 
				createVariables(trace);
									
				// Creation of templates from regions 
				createTemplates(trace);	
									
				// Creating the control template so we can trigger in events (with values)
				createControlTemplate(trace);																											
									
				// Builds the Uppaal model from the elements added above
				trace.builder.buildModel();

				// Crates an Uppaal model editable by SampleRefelcetiveEcoreEditor
				trace.builder.saveUppaalModel(fileURISubstring);									
									
				val filen = UppaalModelSaver.removeFileExtension(fileURISubstring);									
				// Saves the model to an XML file editable by Uppaal
				UppaalModelSerializer.saveToXML(filen);
				// Creates the q file-t
				UppaalQueryGenerator.saveToQ(filen);
									
				// Resets the trace.builder, so the next transformation begins with an empty model
				trace.builder.reset();

			} catch (IncQueryException e) {
				e.printStackTrace();
		}
	}
	
	/**
	 * This method creates Uppaal global variables based on Yakindu internal and interface variables.
	 * Only handles integer and boolean types.
	 * @throws IncQueryException
	 */
	private def createVariables(Trace trace) throws IncQueryException {
		val variableDefinitionsMatcher = trace.engine.getMatcher(VariableDefinitionsQuerySpecification.instance());
		val allVariableDefinitions = variableDefinitionsMatcher.getAllMatches();
		System.out.println("Number of variables: " + allVariableDefinitions.size());
		for (variableMatch : allVariableDefinitions) {
			var expression = new StringBuilder
			if (variableMatch.getIsReadonly()) {
				expression.append("const ");
			}
			if (variableMatch.getType().getName() == "integer") { 
				expression.append("int ");
			}
			else if (variableMatch.getType().getName() == "boolean") {
				expression.append("bool ");
			}
			expression.append(variableMatch.getName() + " ");
			if (variableMatch.getVariable().getInitialValue() == null) {
				expression.append(";");
				trace.builder.addGlobalDeclaration(expression.toString());
			}
			else {
				trace.builder.addGlobalDeclaration(expression.append("=" + UppaalCodeGenerator.transformExpression(variableMatch.getVariable().getInitialValue()) + ";").toString());
			}			
		}
	}
	
	/**
	 * This method creates Uppaal templates based on Yakindu regions.
	 * @throws Exception 
	 */
	private def createTemplates(Trace trace) throws Exception {
		val entryOfRegionsMatcher = trace.engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		// Iterating through regions, and creating the Uppaal elements "matching" the Yakindu elements.
		for (regionMatch : entryOfRegionsMatcher.getAllMatches()) {
			var template = trace.builder.createTemplate(Helper.getTemplateNameFromRegionName(regionMatch.getRegion())); // We name the templates
			// Setting the activity variables of templates
			if (Helper.isTopRegion(regionMatch.getRegion())) {
				trace.builder.addLocalDeclaration("bool " + trace.isActiveVar + " = true;", template);
			} 
			else {
				trace.builder.addLocalDeclaration("bool " + trace.isActiveVar + " = false;", template);
			}			
			// Creating a clock for each template
			trace.builder.addLocalDeclaration("clock " + trace.clockVar + ";", template);
			
			// The region-template pairs are put into the Map
			trace.regionTemplateMap.put(regionMatch.getRegion(), template);
										   									
			// Setting the initial locations (this will change, if the template is not top region equivalent)						 
			var entryLocation = trace.builder.createLocation(regionMatch.getEntry().getKind().getName(), template);
			trace.builder.setInitialLocation(entryLocation, template);
			// The entry node is committed
			trace.builder.setLocationCommitted(entryLocation);
			trace.builder.setLocationComment(entryLocation, "Initial entry node");
	
			// Putting the entry into the map								 
			trace.stateLocationMap.put(regionMatch.getEntry(), entryLocation);
		
			// Creating locations from states
			createLocationsFromStates(regionMatch.getRegion(), template, trace);
			
			// Creating locations from choices
			createLocationsFromChoices(regionMatch.getRegion(), template, trace);
			
			// Creating locations from final states
			createLocationsFromFinalStates(regionMatch.getRegion(), template, trace);
			
			// Creating locations from exit nodes
			createLocationsFromExitNodes(regionMatch.getRegion(), template, trace);			
			
			// Creating edges from same region transitions																		
			createEdges(regionMatch.getRegion(), template, trace);			
		}			
		
		// Creating entry location for each composite state
		createEntryForCompositeStates(trace);
		
		// Creating entry location for each state with entry event
		setEntryEdgeUpdatesFromStates(trace);
		
		// Setting the entry edges of composite states, so every time we enter the state ordinarily (from the same template), all the subtemplates are set properly (isActive = true)
		createEntryEdgesForAbstractionLevels(trace);
		
		// Setting the exit edges of composite states, so every time we leave the state ordinarily (to the same template), all the subtemplates are set properly (isActive = false)
		createExitEdgesForAbstractionLevels(trace);
		
		// Setting the not ordinary (across templates) synchronizations.
		createEdgesForDifferentAbstraction(trace);
		
		// Creating the synchronizations of exit nodes
		createUpdatesForExitNodes(trace);
		
		// Setting the updates of incoming edges of final states
		createFinalStateEdgeUpdates(trace);				

		// Creating edge effects
		setEdgeUpdates(trace);
		
		// Creating edge guards
		setEdgeGuards(trace);		
		
		// Create events as broadcast channels
		createEvents(trace);
		
		// Create loop edges + raising locations as local reactions
		createLocalReactions(trace);
		
		// Creating template guards on every edge: isActive
		createTemplateValidityGuards(trace);
		
		// Transforming after .. expression
		createTimingEvents(trace);
	}
	
	/**
	 * This method creates Uppaal locations based on Yakindu states.
	 * @param region Yakindu region whose states we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private def createLocationsFromStates(Region region, Template template, Trace trace) throws IncQueryException {
		val statesMatcher = trace.engine.getMatcher(StatesQuerySpecification.instance());
		for (stateMatch : statesMatcher.getAllMatches(null, region, null)) {												
			var aLocation = trace.builder.createLocation(stateMatch.getName(), template);
			trace.stateLocationMap.put(stateMatch.getState(), aLocation); // Putting the state-location pairs into the map
			if (Helper.isCompositeState(stateMatch.getState())) {
				trace.builder.setLocationComment(aLocation, "Composite state");
			}
			else {
				trace.builder.setLocationComment(aLocation, "Simple state");
			}												
		}
	}
	
	/**
	 * This method creates Uppaal (committed) locations based on Yakindu choices.
	 * @param region Yakindu region whose choices we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private def createLocationsFromChoices(Region region, Template template, Trace trace) throws IncQueryException {
		// To guarantee unique names
		var id = 0; 
		val choicesMatcher = trace.engine.getMatcher(ChoicesQuerySpecification.instance());
		for (choiceMatch : choicesMatcher.getAllMatches(null, region)) {				
			var aLocation = trace.builder.createLocation("Choice" + id++, template);
			trace.builder.setLocationCommitted(aLocation);
			trace.stateLocationMap.put(choiceMatch.getChoice(), aLocation); // Putting the choice-location pairs into the map	
			trace.builder.setLocationComment(aLocation, "A choice");
		}
	}
	
	/**
	 * This method creates Uppaal locations based on Yakindu final states.
	 * @param region Yakindu region whose final states we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private def createLocationsFromFinalStates(Region region, Template template, Trace trace) throws IncQueryException {
		// To guarantee unique names
		var id = 0; 
		val finalStatesMatcher = trace.engine.getMatcher(FinalStatesQuerySpecification.instance());
		for (FinalStatesMatch finalStateMatch : finalStatesMatcher.getAllMatches(null, region)) {										
			var aLocation = trace.builder.createLocation("FinalState" + id++, template);
			trace.stateLocationMap.put(finalStateMatch.getFinalState(), aLocation); // Putting the  final state-location pairs into the map		
			trace.builder.setLocationComment(aLocation, "A final state");
		}
	}
	
	/**
	 * This method creates end = false update on each incoming edge of the final state locations.
	 * @throws IncQueryException
	 */
	private def createFinalStateEdgeUpdates(Trace trace) throws IncQueryException {
		val finalStateEdgeMatcher = trace.engine.getMatcher(FinalStateEdgeQuerySpecification.instance());
		for (finalStateEdgeMatch : finalStateEdgeMatcher.getAllMatches()) {
			trace.builder.setEdgeUpdate(trace.transitionEdgeMap.get(finalStateEdgeMatch.getIncomingEdge()), trace.endVar + " = true");
		}
	}
	
	/**
	 * This method creates Uppaal locations based on Yakindu exit nodes.
	 * @param region Yakindu region whose final states we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private def createLocationsFromExitNodes(Region region, Template template, Trace trace) throws IncQueryException {
		// To guarantee unique names
		var id = 0;
		val exitNodesMatcher = trace.engine.getMatcher(ExitNodesQuerySpecification.instance());
		for (exitNodesMatch : exitNodesMatcher.getAllMatches(null, region)) {
			var exitNode = trace.builder.createLocation("ExitNode" + (id++), template);
			trace.stateLocationMap.put(exitNodesMatch.getExit(), exitNode); // Putting the  exit node-location pairs into the map		
			trace.builder.setLocationComment(exitNode, "An exit node");
		}
	}
	
	/**
	 * This method creates ! synchronizations on incoming edges of exit nodes, and ? synchronization on the default transition of the composite state.
	 * @throws Exception 
	 */
	private def createUpdatesForExitNodes(Trace trace) throws Exception {
		var id = 0;
		val exitNodeSyncMatcher = trace.engine.getMatcher(ExitNodeSyncQuerySpecification.instance());
		for (exitNodesMatch : exitNodeSyncMatcher.getAllMatches()) {
			trace.syncChanId = trace.syncChanId + 1
			trace.builder.addGlobalDeclaration("broadcast chan " + trace.syncChanVar + (trace.syncChanId) + ";");
			var exitNodeEdge = trace.transitionEdgeMap.get(exitNodesMatch.getExitNodeTransition());
			trace.builder.setEdgeSync(exitNodeEdge, trace.syncChanVar + (trace.syncChanId), true);
			if (trace.transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()).getSynchronization() != null ) {
				if (trace.transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()).getSynchronization().getKind().getValue() == 0) {
					throw new Exception("? sync on default edge of exit node");
				}				
				var syncEdge = createSyncLocation(trace.builder.getEdgeTarget(trace.transitionEdgeMap.get(exitNodesMatch.getDefaultTransition())), "exitNodeSyncLoc" + (id++), trace.transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()).getSynchronization(), trace);
				trace.builder.setEdgeTarget(trace.transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()), trace.builder.getEdgeSource(syncEdge));				
			}
			trace.builder.setEdgeSync(trace.transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()), trace.syncChanVar + (trace.syncChanId), false);
			// The subregions should not be prohibitied, as the outgoing edge of the composite state location automatically does it
		}
	}
	
	/**
	 * This method creates Uppaal edges based on Yakindu transitions whose source and target are in the same region.
	 * @param region Yakindu region whose transitions we want to process
	 * @param template Uppaal template this method puts the edges into
	 * @throws Exception
	 */
	private def createEdges(Region region, Template template, Trace trace) throws Exception {
		val edgesInSameRegionMatcher = trace.engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());
		for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, null, region)) {											
			// If both the source and target are in the given region
			if (!(trace.stateLocationMap.containsKey(edgesInSameRegionMatch.getSource()) && trace.stateLocationMap.containsKey(edgesInSameRegionMatch.getTarget()))) {								
				throw new Exception("The source or the target is null.");
			}
			var anEdge = trace.builder.createEdge(template);
			anEdge.setSource(trace.stateLocationMap.get(edgesInSameRegionMatch.getSource()));
			anEdge.setTarget(trace.stateLocationMap.get(edgesInSameRegionMatch.getTarget()));
			trace.transitionEdgeMap.put(edgesInSameRegionMatch.getTransition(), anEdge); // Putting the  transition-edge pairs into the map							
		}
	}
	
	/**
	 * This method creates the entry location of composite states, so subregion activation may take place at every ordinary enter.
	 * @throws Exception 
	 */
	private def createEntryForCompositeStates(Trace trace) throws Exception {
		val compositeStatesMatcher = trace.engine.getMatcher(CompositeStatesQuerySpecification.instance());
		val sourceAndTargetOfTransitionsMatcher = trace.engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (compositeStateMatch : compositeStatesMatcher.getAllMatches()) {				
			// Create entry location
			var stateEntryLocation = createEntryLocation(compositeStateMatch.getCompositeState(), compositeStateMatch.getParentRegion(), trace);
			// Set the targets of each incoming edge to the entry location
			for (sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, null, compositeStateMatch.getCompositeState())) {
				// Only same region edges
				if ((trace.transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition()))) {
					trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).setTarget(stateEntryLocation);
				}	
				
			}
		}
	}
	
	/**
	 * This method creates a committed entry location for a state.
	 * @param vertex Yakindu vertex, that an entry location is needed to
	 * @param region Yakindu region, a parent region of the vertex
	 * @return Uppaal location, entry location of the given state
	 */
	private def createEntryLocation(Vertex vertex, Region region, Trace trace) {
		trace.entryStateId = trace.entryStateId + 1
		var stateEntryLocation = trace.builder.createLocation("EntryLocationOf" + vertex.getName() + (trace.entryStateId), trace.regionTemplateMap.get(region));
		trace.builder.setLocationCommitted(stateEntryLocation);
		var entryEdge = trace.builder.createEdge(trace.regionTemplateMap.get(region));
		trace.builder.setEdgeSource(entryEdge, stateEntryLocation);
		trace.builder.setEdgeTarget(entryEdge, trace.stateLocationMap.get(vertex));
		trace.hasEntryLoc.put(vertex, entryEdge); // Putting the  vertex-edge pairs into the map		
		return stateEntryLocation;
	}
	
	/**
	 * This method creates ! sync on each entry location edge of composite states, and ? syncs in each subregion of composite states.
	 * @throws Exception 
	 */
	private def createEntryEdgesForAbstractionLevels(Trace trace) throws Exception {
		val compositeStatesMatcher = trace.engine.getMatcher(CompositeStatesQuerySpecification.instance());
		for (compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			trace.syncChanId = trace.syncChanId + 1
			trace.builder.addGlobalDeclaration("broadcast chan " + trace.syncChanVar + (trace.syncChanId) + ";");
			// Entry location edges must already be created
			var entryEdge = trace.hasEntryLoc.get(compositeStateMatch.getCompositeState());
			trace.builder.setEdgeSync(entryEdge, trace.syncChanVar + (trace.syncChanId), true);
			for (subregion : compositeStateMatch.getCompositeState().getRegions()) {
				generateNewInitLocation(subregion, trace);
			}
			// Create ? syncs in all subregions
			setAllRegionsWithSync(true, compositeStateMatch.getCompositeState().getRegions(), trace);			
		}
	}
	
	/**
	 * This method creates ! sync on each outgoing edge of composite states, and ? syncs in each region whose ancestor is the composite states.
	 * @throws Exception 
	 */
	private def createExitEdgesForAbstractionLevels(Trace trace) throws Exception {
		var id = 0;
		val compositeStatesMatcher = trace.engine.getMatcher(CompositeStatesQuerySpecification.instance());
		val sourceAndTargetOfTransitionsMatcher = trace.engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			trace.syncChanId = trace.syncChanId + 1
			trace.builder.addGlobalDeclaration("broadcast chan " + trace.syncChanVar + (trace.syncChanId) + ";");
			// Each outgoing edge must be updated by the exiting sync
			for (sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, compositeStateMatch.getCompositeState(), null)) {				
				if (trace.transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition())) { // So we investigate only same region edges
					if (trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).getSynchronization() == null) {
						trace.builder.setEdgeSync(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), trace.syncChanVar + (trace.syncChanId), true);
					}
					// If the outgoing edge already has a sync, a syncing location is created
					else {
						var syncEdge = createSyncLocation(trace.stateLocationMap.get(sourceAndTargetOfTransitionsMatch.getTarget()), "CompositeSyncLocation" + (id += 1), null, trace);
						trace.builder.setEdgeSync(syncEdge, trace.syncChanVar + (trace.syncChanId), true);
						trace.builder.setEdgeTarget(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), trace.builder.getEdgeSource(syncEdge));
					}	
				}
			}
			// Disabling all regions below it		
			setEdgeExitAllSubregions(compositeStateMatch.getCompositeState() as Vertex, new ArrayList<Region>, trace)
		}
	}
	
	/**
	 * Creates ? sync edges and isActive updates in all regions given in the list. These edges may lead to the init location or to their sources.
	 * @param toBeTrue True if region activation is needed, false if region disabling is needed
	 * @param regionList List of Yakindu regions that need to be synced
	 * @throws Exception 
	 */
	private def setAllRegionsWithSync(boolean toBeTrue, List<Region> regionList, Trace trace) throws Exception {
		val verticesOfRegionsMatcher = trace.engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		for (subregion : regionList) {	
			for (verticesOfRegionMatch : verticesOfRegionsMatcher.getAllMatches(subregion, null)) {
				// No loop edge from choice or entry locations
				if (!(Helper.isChoice(verticesOfRegionMatch.getVertex())) && !(Helper.isEntry(verticesOfRegionMatch.getVertex()))) {
					var syncEdge = trace.builder.createEdge(trace.regionTemplateMap.get(subregion));
					trace.builder.setEdgeSync(syncEdge, trace.syncChanVar + trace.syncChanId, false);
					if (toBeTrue) {
						trace.builder.setEdgeUpdate(syncEdge, trace.isActiveVar + " = true");
					}
					else {
						trace.builder.setEdgeUpdate(syncEdge, trace.isActiveVar + " = false");
					}
					trace.builder.setEdgeSource(syncEdge, trace.stateLocationMap.get(verticesOfRegionMatch.getVertex()));
					// In case of entry, we investigate where the sync edge must be connected depending on history indicators
					if (toBeTrue) {
						if (Helper.hasHistory(subregion)) {
							if (trace.hasEntryLoc.containsKey(verticesOfRegionMatch.getVertex())) {
								trace.builder.setEdgeTarget(syncEdge, trace.hasEntryLoc.get(verticesOfRegionMatch.getVertex()).getSource());
							}
							else {
								trace.builder.setEdgeTarget(syncEdge, trace.stateLocationMap.get(verticesOfRegionMatch.getVertex()));
							}
						}
						else {
							trace.builder.setEdgeTarget(syncEdge, trace.stateLocationMap.get(Helper.getEntryOfRegion(subregion)));
						}
					}
					// In case of exit the sync edge must be a loop edge
					else {
						trace.builder.setEdgeTarget(syncEdge, trace.stateLocationMap.get(verticesOfRegionMatch.getVertex()));
						setHelperEdgeExitEvent(syncEdge, verticesOfRegionMatch.getVertex(), trace);
					}
				}
			}			
		}
	}
	
	/**
	 * This method creates a init location int the mapped region (in the template equivalent) and connects it to the entry of the region.
	 * @param region The region whose template equivalent will get the generated init location
	 * @throws IncQueryException
	 */
	private def generateNewInitLocation(Region region, Trace trace) throws IncQueryException {
		var initLocation = trace.builder.createLocation("GeneratedInit", trace.regionTemplateMap.get(region));
		var syncEdge = trace.builder.createEdge(trace.regionTemplateMap.get(region));
		trace.builder.setEdgeSync(syncEdge, trace.syncChanVar + trace.syncChanId, false);
		trace.builder.setEdgeUpdate(syncEdge, trace.isActiveVar + " = true");
		trace.builder.setEdgeSource(syncEdge, initLocation);
		trace.builder.setEdgeTarget(syncEdge, trace.stateLocationMap.get(Helper.getEntryOfRegion(region)));
		trace.builder.setInitialLocation(initLocation, trace.regionTemplateMap.get(region));
		trace.hasInitLoc.put(trace.regionTemplateMap.get(region), initLocation);
	}
	
	/**
	 * Creates edges whose source and target are not in the same region.
	 * @throws Exception 
	 */
	private def createEdgesForDifferentAbstraction(Trace trace) throws Exception {
		val edgesAcrossRegionsMatcher = trace.engine.getMatcher(EdgesAcrossRegionsQuerySpecification.instance());
		for (acrossTransitionMatch : edgesAcrossRegionsMatcher.getAllMatches()) {											
			// The level of both endpoints must be investigated and further action must be taken accordingly
			if (!(trace.stateLocationMap.containsKey(acrossTransitionMatch.getSource()) && trace.stateLocationMap.containsKey(acrossTransitionMatch.getTarget()))) {								
				throw new Exception("The target or the source is not mapped: " + acrossTransitionMatch.getSource() + " " + trace.stateLocationMap.containsKey(acrossTransitionMatch.getTarget()));				
			}
			var sourceLevel = Helper.getLevelOfVertex(acrossTransitionMatch.getSource());
			var targetLevel = Helper.getLevelOfVertex(acrossTransitionMatch.getTarget());
			if (sourceLevel < targetLevel) {
				createEdgesWhenSourceLesser(acrossTransitionMatch.getSource(), acrossTransitionMatch.getTarget(), acrossTransitionMatch.getTransition(), targetLevel, targetLevel - sourceLevel, new ArrayList<Region>(), trace);							
			}						
			if (sourceLevel > targetLevel) {
				createEdgesWhenSourceGreater(acrossTransitionMatch.getSource(), acrossTransitionMatch.getTarget(), acrossTransitionMatch.getTransition(), sourceLevel, new ArrayList<Region>(), trace);
			}
		}		
	}
	
	/**
	 * Creates edges needed for the transitions across regions.
	 * Works only if the level of the source is smaller than the level of the target.
	 * @param source Yakindu vertex, source of the transition
	 * @param target Yakindu vertex, target of the transition
	 * @param transition Yakindu transition that crosses regions
	 * @param lastLevel Integer, indicating the level of the target.
	 * @throws Exception 
	 */ 
	private def void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions, Trace trace) throws Exception {
		// Recursion:
		// Going back to the top level, and from there on each level syncs are created
		if (source.getParentRegion() != target.getParentRegion()) {
			visitedRegions.add(target.getParentRegion())
			createEdgesWhenSourceLesser(source, target.getParentRegion().getComposite() as Vertex, transition, lastLevel, levelDifference, visitedRegions, trace);
		}
		// If top level is reached:
		if (source.getParentRegion() == target.getParentRegion()) {
			trace.syncChanId = trace.syncChanId + 1 
			trace.builder.addGlobalDeclaration("broadcast chan " + trace.syncChanVar + (trace.syncChanId) + ";"); // New sync variable is created
			// Edge is created with the new sync on it
			var abstractionEdge = trace.builder.createEdge(trace.regionTemplateMap.get(source.getParentRegion()));
			trace.builder.setEdgeSource(abstractionEdge, trace.stateLocationMap.get(source));
			trace.builder.setEdgeTarget(abstractionEdge, trace.stateLocationMap.get(target));
			trace.builder.setEdgeSync(abstractionEdge, trace.syncChanVar + (trace.syncChanId), true);
			trace.builder.setEdgeComment(abstractionEdge, "In Yakindu this edge leads to a vertex in another region. (Lower abstraction)");
			// If the target has entry event, it must be written onto the edge
			setHelperEdgeEntryEvent(abstractionEdge, target, lastLevel, trace); 
			// This edge is the mapped edge of the across region transition	
			trace.transitionEdgeMap.put(transition, abstractionEdge);
			// If the target is a composite state, we enter all its subregions except the visited ones
			setEdgeEntryAllSubregions(target, visitedRegions, trace);
			// If the source is a composite state, all subregions must be disabled
			if (Helper.isCompositeState(source)) {
				setEdgeExitAllSubregions(source, new ArrayList<Region>(), trace);
			}
		}	
		// If we are not in top region, ? synced edges are created from EVERY location and connected to the target (so enter is possible no matter the history)
		else {
			val verticesOfRegionsMatcher = trace.engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
			for (verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches(target.getParentRegion(), null)) {				
				setHelperEdges(trace.stateLocationMap.get(verticesOfRegionsMatch.getVertex()), target, lastLevel, trace);
			}
			// Subtemplate's "initial location" is connected to the right location
			if (trace.hasInitLoc.containsKey(trace.regionTemplateMap.get(target.getParentRegion()))) {
				setHelperEdges(trace.hasInitLoc.get(trace.regionTemplateMap.get(target.getParentRegion())), target, lastLevel, trace);				
			}
			// If the target is a composite state, we enter all its regions apart from this one
			// Except if it is the last level: then we enter the state ordinarily
			if (lastLevel != Helper.getLevelOfVertex(target) && Helper.isCompositeState(target)) {
				setEdgeEntryAllSubregions(target, visitedRegions, trace);
			}
		}		
	}
	
	/**
	 * This method creates a synchrnoizations from the generated init location to the first normal state.
	 * @param visitedRegions Those Yakindu regions whose template equivalents we want to create the channels in.
	 * @throws Exception
	 */
	private def setSyncFromGeneratedInit(List<Region> visitedRegions, Trace trace) throws Exception {
		for (subregion: visitedRegions) {
			if (!(trace.hasInitLoc.containsKey(trace.regionTemplateMap.get(subregion)))) {
				throw new Exception("No initial location: " + subregion.getName());
			}
			else {
				var fromGeneratedInit = trace.builder.createEdge(trace.regionTemplateMap.get(subregion));
				trace.builder.setEdgeSource(fromGeneratedInit, trace.hasInitLoc.get(trace.regionTemplateMap.get(subregion)));
				trace.builder.setEdgeTarget(fromGeneratedInit, trace.stateLocationMap.get(Helper.getEntryOfRegion(subregion)));
				trace.builder.setEdgeSync(fromGeneratedInit, trace.syncChanVar + trace.syncChanId, false);
				trace.builder.setEdgeUpdate(fromGeneratedInit, trace.isActiveVar + " = true");
			}
		}
	}
	
	/**
	 * This method builds ? synchronization edges from the given source to the given target.
	 * @param source Uppaal location 
	 * @param target Yakindu target whose Uppaal equivalent the edge will be connected to
	 * @param lastLevel Integer indicating the lowest abstraction level in the given mapping
	 * @throws IncQueryException
	 */
	private def setHelperEdges(Location source, Vertex target, int lastLevel, Trace trace) throws IncQueryException {
		var syncEdge = trace.builder.createEdge(source.getParentTemplate());					
		trace.builder.setEdgeSource(syncEdge, source);		
		// On the last level if the target has an entry location, the edge must be connected to it
		if (lastLevel == Helper.getLevelOfVertex(target) && trace.hasEntryLoc.containsKey(target)) {
			trace.builder.setEdgeTarget(syncEdge, trace.builder.getEdgeSource(trace.hasEntryLoc.get(target)));
		}							
		// On other levels it MUST NOT be connected to the entry location for it might spoil the whole mappping
		else {					
			trace.builder.setEdgeTarget(syncEdge, trace.stateLocationMap.get(target));
		}
		// If the target has an entry event, it must be written onto the edge
		setHelperEdgeEntryEvent(syncEdge, target, lastLevel, trace);
		trace.builder.setEdgeSync(syncEdge, trace.syncChanVar + (trace.syncChanId), false);
		trace.builder.setEdgeUpdate(syncEdge, trace.isActiveVar + " = true");
	}
	
	/**
	 * This method writes the entry event of the given target onto the given edge
	 * @param edge Uppaal edge
	 * @param target Yakindu target whose Uppaal equivalent the edge will be connected to
	 * @param lastLevel Integer indicating the lowest abstraction level in the given mapping
	 * @throws IncQueryException
	 */	
	private def setHelperEdgeEntryEvent(Edge edge, Vertex target, int lastLevel, Trace trace) throws IncQueryException {
		if (Helper.hasEntryEvent(target) && lastLevel != Helper.getLevelOfVertex(target)) {
			for (statesWithEntryEventMatch : trace.runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
				if (statesWithEntryEventMatch.getState() == target) {
					val effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
					trace.builder.setEdgeUpdate(edge, effect);
				}
			}
		}
	}
	
	/**
	 * This method creates all the entry helper edges for the given target.
	 * @param target Yakindu target whose subregions will get the helper edges
	 * @param visitedRegions List of Yakindu regions that will NOT get edges
	 * @throws Exception
	 */
	private def setEdgeEntryAllSubregions(Vertex target, List<Region> visitedRegions, Trace trace) throws Exception {		
		var pickedSubregions = new ArrayList<Region>((target as State).getRegions());
		pickedSubregions.removeAll(visitedRegions);
		setAllRegionsWithSync(true, pickedSubregions, trace);		
		setSyncFromGeneratedInit(pickedSubregions, trace);
	}
	
	/**
	 * Creates edges needed for the transitions across regions.
	 * Works only if the level of the source is greater than the level of the target.
	 * @param source Yakindu vertex, source of the transition
	 * @param target Yakindu vertex, target of the transition
	 * @param transition Yakindu transition that crosses regions
	 * @param lastLevel Integer, indicating the level of the source
	 * @throws Exception 
	 */ 
	def void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel, List<Region> visitedRegions, Trace trace) throws Exception {
		// On the lowest level a loop edge is created
		if (Helper.getLevelOfVertex(source) == lastLevel) {
			visitedRegions.add(source.getParentRegion());
			// Creating a sync channel
			trace.syncChanId = trace.syncChanId + 1
			trace.builder.addGlobalDeclaration("broadcast chan " + trace.syncChanVar + (trace.syncChanId) + ";");
			var ownSyncEdge = trace.builder.createEdge(trace.regionTemplateMap.get(source.getParentRegion()));
			trace.builder.setEdgeSource(ownSyncEdge, trace.stateLocationMap.get(source));
			trace.builder.setEdgeTarget(ownSyncEdge, trace.stateLocationMap.get(source));
			trace.builder.setEdgeSync(ownSyncEdge, trace.syncChanVar + (trace.syncChanId), true);
			// Diasabling this region beacause it cannot synchronize to itself
			trace.builder.setEdgeUpdate(ownSyncEdge, trace.isActiveVar + " = false");
			trace.builder.setEdgeComment(ownSyncEdge, "In Yakindu this edge leads to a vertex in another region. (Higher abstraction)");			 
			// This edge is the mapped edge of the across region transition	
			trace.transitionEdgeMap.put(transition, ownSyncEdge);
			createEdgesWhenSourceGreater(source.getParentRegion().getComposite() as Vertex, target, transition, lastLevel, visitedRegions, trace);
		}
		// The top level
		else if (Helper.getLevelOfVertex(source) == Helper.getLevelOfVertex(target)) {
			// On the top level an edge is created to receive synchronization
			var ownSyncEdge = trace.builder.createEdge(trace.regionTemplateMap.get(source.getParentRegion()));
			trace.builder.setEdgeSource(ownSyncEdge, trace.stateLocationMap.get(source));
			// If the target has entry loc, that must be the edge targer
			if (trace.hasEntryLoc.containsKey(target)) {
				trace.builder.setEdgeTarget(ownSyncEdge, trace.builder.getEdgeSource(trace.hasEntryLoc.get(target)));
			}
			else {
				trace.builder.setEdgeTarget(ownSyncEdge, trace.stateLocationMap.get(target));
			}
			trace.builder.setEdgeSync(ownSyncEdge, trace.syncChanVar + (trace.syncChanId), false);
			// The edge gets an update if the state has exit event
			setHelperEdgeExitEvent(ownSyncEdge, source, trace);
			// All descendant regions are disabled except for the visited ones
			setEdgeExitAllSubregions(source, visitedRegions, trace);
			return;
		}
		// On other levels we create sync edges, disable the region and write exit event on them
		else {
			visitedRegions.add(source.getParentRegion());
			var ownSyncEdge = trace.builder.createEdge(trace.regionTemplateMap.get(source.getParentRegion()));
			trace.builder.setEdgeSource(ownSyncEdge, trace.stateLocationMap.get(source));
			trace.builder.setEdgeTarget(ownSyncEdge, trace.stateLocationMap.get(source));
			trace.builder.setEdgeSync(ownSyncEdge, trace.syncChanVar + (trace.syncChanId), false);
			trace.builder.setEdgeUpdate(ownSyncEdge, trace.isActiveVar + " = false");
			setHelperEdgeExitEvent(ownSyncEdge, source, trace);
			createEdgesWhenSourceGreater(source.getParentRegion().getComposite() as Vertex, target, transition, lastLevel, visitedRegions, trace);
		}
	}
	
	/**
	 * This method writes the exit event of the given vertex on the given edge 
	 * @param edge Uppaal edge
	 * @param source Yakindu vertex
	 * @throws IncQueryException
	 */
	private def setHelperEdgeExitEvent(Edge edge, Vertex source, Trace trace) throws IncQueryException {
		if (Helper.hasExitEvent(source)) {
			for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : trace.runOnceEngine.getAllMatches(StatesWithExitEventWithoutOutgoingTransitionMatcher.querySpecification())) {
				if (statesWithExitEventMatch.getState() == source) {
					val effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());
					trace.builder.setEdgeUpdate(edge, effect);						
				}
			}
		}
	}
	
	/**
	 * This method disables all the descendant regions of the given vertex.
	 * @param source Yakindu vertex
	 * @param regionsToRemove List of Regions that we do not want to disable
	 * @throws Exception
	 */
	private def setEdgeExitAllSubregions(Vertex source, List<Region> regionsToRemove, Trace trace) throws Exception {
		var subregionList = new ArrayList<Region>();
		Helper.addAllSubregionsToRegionList(source as State, subregionList);
		subregionList.removeAll(regionsToRemove);
		setAllRegionsWithSync(false, subregionList, trace);
	}
	
	/**
	 * This method creates updates on Uppaal edges based on the Yakindu model.
	 * @throws Exception 
	 */
	private def setEdgeUpdates(Trace trace) throws Exception {
		// Transitions with effects
		val edgesWithEffectMatcher = trace.engine.getMatcher(EdgesWithEffectQuerySpecification.instance());
		for (EdgesWithEffectMatch edgesWithEffectMatch : edgesWithEffectMatcher.getAllMatches()) {
			val effect = UppaalCodeGenerator.transformExpression(edgesWithEffectMatch.getExpression());
			trace.builder.setEdgeUpdate(trace.transitionEdgeMap.get(edgesWithEffectMatch.getTransition()), effect);
		}
		// Creating entry and exit event updates
		setExitEdgeUpdatesFromStates(trace);
		// Creating raising events
		createRaisingEventSyncs(trace);
	}
	
	/**
	 * This method sets updates on outgoing edges of states with exit event.
	 * @throws IncQueryException 
	 */
	private def setExitEdgeUpdatesFromStates(Trace trace) throws IncQueryException {
		for (statesWithExitEventMatch : trace.runOnceEngine.getAllMatches(StatesWithExitEventMatcher.querySpecification())) {
			val effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());			
			trace.builder.setEdgeUpdate(trace.transitionEdgeMap.get(statesWithExitEventMatch.getTransition()), effect);			
		}
	}
	
	/**
	 * This method creates entry locations for all states with entry event and writes the update onto the entry edge.
	 * The target of the right same region edges are set to the entry location.
	 * @throws IncQueryException
	 */
	private def setEntryEdgeUpdatesFromStates(Trace trace) throws IncQueryException {
		val edgesInSameRegionMatcher = trace.engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());		
		for (statesWithEntryEventMatch : trace.runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
			// Transforming the expression
			val effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
			// If it has no entry location yet
			if (!trace.hasEntryLoc.containsKey(statesWithEntryEventMatch.getState())) {
				// Creating the entry location
				var stateEntryLocation = createEntryLocation(statesWithEntryEventMatch.getState(), statesWithEntryEventMatch.getParentRegion(), trace);
				trace.builder.setEdgeUpdate(trace.hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
				// Set the target of the incoming edges
				for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, statesWithEntryEventMatch.getState(), null)) {				
					trace.builder.setEdgeTarget(trace.transitionEdgeMap.get(edgesInSameRegionMatch.getTransition()), stateEntryLocation);				
				}
			}
			// If it already has an entry location
			else {
				trace.builder.setEdgeUpdate(trace.hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
			}
		}
	}
	
	
	/**
	 * This method transforms each local reaction to a loop edge or a circle of two edges with a sync location if a raising expression is present (createRaisingLocationForLocalReaction method).
	 * The source and the target of the loop is the location equivalent of the parent state of the local reaction.
	 * @throws Exception
	 */
	private def createLocalReactions(Trace trace) throws Exception {
		var guard = "";
		// Local reactions with guards only
		for (LocalReactionOnlyGuardMatch localReactionValueOfGuardMatch : trace.runOnceEngine.getAllMatches(LocalReactionOnlyGuardMatcher.querySpecification())) {
			var stateLocation = trace.stateLocationMap.get(localReactionValueOfGuardMatch.getState());
			var localReactionEdge = trace.builder.createEdge(stateLocation.getParentTemplate());
			trace.builder.setEdgeSource(localReactionEdge, stateLocation);
			trace.builder.setEdgeTarget(localReactionEdge, stateLocation);	
			// In case valueof is used in Yakindu local reaction
			if (Helper.isEventName(localReactionValueOfGuardMatch.getName())) {
				guard = Helper.getInEventValueName(localReactionValueOfGuardMatch.getName()) + " " + localReactionValueOfGuardMatch.getOperator().getLiteral() + " " + UppaalCodeGenerator.transformExpression(localReactionValueOfGuardMatch.getGuardRightOperand());
				trace.builder.setEdgeSync(localReactionEdge, localReactionValueOfGuardMatch.getName(), false);
			} 
			else {
				guard = localReactionValueOfGuardMatch.getName() + " " + localReactionValueOfGuardMatch.getOperator().getLiteral() + " " + UppaalCodeGenerator.transformExpression(localReactionValueOfGuardMatch.getGuardRightOperand());				
			}
			trace.builder.setEdgeGuard(localReactionEdge, guard);
			// Raising events
			createRaisingLocationForLocalReaction(localReactionValueOfGuardMatch.getLocalReaction(), localReactionEdge, trace);
		}
		// Local reactions that are not guard only (Trigger / Trigger + Guard
		for (LocalReactionPlainMatch localReactionPlainMatch : trace.runOnceEngine.getAllMatches(LocalReactionPlainMatcher.querySpecification())) {
			var stateLocation = trace.stateLocationMap.get(localReactionPlainMatch.getState());
			var localReactionEdge = trace.builder.createEdge(stateLocation.getParentTemplate());
			trace.builder.setEdgeSource(localReactionEdge, stateLocation);
			trace.builder.setEdgeTarget(localReactionEdge, stateLocation);
			trace.builder.setEdgeSync(localReactionEdge, UppaalCodeGenerator.transformExpression(localReactionPlainMatch.getExpression()), false);
			// Handling the guards
			if (localReactionPlainMatch.getReactionTrigger().getGuard() != null) {
				guard = UppaalCodeGenerator.transformExpression(localReactionPlainMatch.getReactionTrigger().getGuard().getExpression());
				trace.builder.setEdgeGuard(localReactionEdge, guard);
			}
			// Raising events
			createRaisingLocationForLocalReaction(localReactionPlainMatch.getLocalReaction(), localReactionEdge, trace);
		}
		
	}
	
	/**
	 * This method creates a raising sync edge if the local reaction has an event raising expression.
	 * @param localReaction The Yakindu local reaction, that we investigate whether it has an event raising expression
	 * @param localReactionEdge The Uppaal loop edge that contains the local reaction trigger/guard.
	 * @throws Exception
	 */
	private def createRaisingLocationForLocalReaction(LocalReaction localReaction, Edge localReactionEdge, Trace trace) throws Exception {
		val localReactionValueOfEffectMatcher = trace.engine.getMatcher(LocalReactionValueOfEffectQuerySpecification.instance());
		for (LocalReactionValueOfEffectMatch localReactionValueOfEffectMatch : localReactionValueOfEffectMatcher.getAllMatches(localReaction, null)) {
			trace.builder.setEdgeUpdate(localReactionEdge, UppaalCodeGenerator.transformExpression(localReactionValueOfEffectMatch.getAction()));
			if (localReactionValueOfEffectMatch.getAction() instanceof EventRaisingExpression) {
				val eventRaisingExpression = localReactionValueOfEffectMatch.getAction() as EventRaisingExpression ;
				trace.raiseId = trace.raiseId + 1
				var syncEdge = createSyncLocationWithString(localReactionEdge.getTarget(), "Raise_" + UppaalCodeGenerator.transformExpression(eventRaisingExpression.getEvent()) + (trace.raiseId), UppaalCodeGenerator.transformExpression(eventRaisingExpression.getEvent()), trace);
				trace.builder.setEdgeTarget(localReactionEdge, syncEdge.getSource());
				if (eventRaisingExpression.getValue() != null) {
					trace.builder.setEdgeUpdate(localReactionEdge, Helper.getInEventValueName(UppaalCodeGenerator.transformExpression(eventRaisingExpression.getEvent())) + " = " + UppaalCodeGenerator.transformExpression(eventRaisingExpression.getValue()));
				}
			}
		}
	}
	
	/**
	 * This method transforms guards of Yakindu transitions and places them on their Uppaal edge equivalents.
	 * @throws IncQueryException 
	 */
	private def setEdgeGuards(Trace trace) throws IncQueryException {
		val edgesWithGuardMatcher = trace.engine.getMatcher(EdgesWithGuardQuerySpecification.instance());
		val eventsWithTypeMatcher = trace.engine.getMatcher(EventsWithTypeQuerySpecification.instance());
		for (EdgesWithGuardMatch edgesWithGuardMatch : edgesWithGuardMatcher.getAllMatches()) {
			// Transforming the guard and placing it onto the edge equivalent
			var guard = " " + UppaalCodeGenerator.transformExpression(edgesWithGuardMatch.getExpression());
			for (EventsWithTypeMatch eventsWithTypeMatch : eventsWithTypeMatcher.getAllMatches()) {
				// If the guard expression cointains an in event variable, then it has to be replaced by the in event variable.
				if (guard.contains(" " + eventsWithTypeMatch.getName() + " ")) {
					guard = guard.replaceAll(" " + eventsWithTypeMatch.getName() + " ", " " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + " ");	
					trace.builder.setEdgeSync(trace.transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), eventsWithTypeMatch.getName(), false);
				}
			}
			// So we can concatenate guards
			if (trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != null && trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != "") {
				trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) + " && " + guard);
			}
			else {					
				trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), guard);
			}		
		}
	}
	
	/**
	 * This method creates an "isActive" guard on each edge in the Uppaal model, so a transition of a template can only fire if the template is active.
	 * @throws IncQueryException 
	 */
	private def createTemplateValidityGuards(Trace trace) throws IncQueryException {
		val sourceAndTargetOfTransitionsMatcher = trace.engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches()) {
			// So regularly transformed guards are not deleted
			if (trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != null && trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != "") {
				trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), trace.isActiveVar + " && " + "( " + trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) + " )");
				if (Helper.hasFinalState) {
					trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) + " && !" + trace.endVar)
				}
			} 
			else {
				trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), trace.isActiveVar);
				if (Helper.hasFinalState) {
					trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), trace.builder.getEdgeGuard(trace.transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) + " && !" + trace.endVar)
				}
			}		
		}
	}
	
	/**
	 * Creates events as synchronization channels.
	 * @throws IncQueryException
	 */
	private def createEvents(Trace trace) throws IncQueryException {
		val eventsMatcher = trace.engine.getMatcher(EventsQuerySpecification.instance());
		for (eventsMatch : eventsMatcher.getAllMatches()) {
			trace.builder.addGlobalDeclaration("broadcast chan " + eventsMatch.getEventName() + ";");
		}
	}
	
	/**
	 * This method creates raise events as another edge synchronizations where it is needed.
	 * (If more than one raising event on an edge, the result will be linked committed locations with synched edges.)
	 * @throws Exception. 
	 */
	private def createRaisingEventSyncs(Trace trace) throws Exception {
		val edgesWithRaisingEventMatcher = trace.engine.getMatcher(EdgesWithRaisingEventQuerySpecification.instance());
		val raisingExpressionsWithAssignmentMatcher = trace.engine.getMatcher(RaisingExpressionsWithAssignmentQuerySpecification.instance());
		for (edgesWithRaisingEventMatch : edgesWithRaisingEventMatcher.getAllMatches()) {
			trace.raiseId = trace.raiseId + 1
			var raiseEdge = createSyncLocationWithString(trace.transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()).getTarget(), "Raise_" + edgesWithRaisingEventMatch.getName() + (trace.raiseId), edgesWithRaisingEventMatch.getName(), trace);
			trace.builder.setEdgeTarget(trace.transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()), raiseEdge.getSource());
			// Now we apply the updates if the raised event is an in event
			for (RaisingExpressionsWithAssignmentMatch raisingExpressionsWithAssignmentMatch : raisingExpressionsWithAssignmentMatcher.getAllMatches(edgesWithRaisingEventMatch.getTransition(), edgesWithRaisingEventMatch.getElement(), null, null)) {
				 trace.builder.setEdgeUpdate(trace.transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()), Helper.getInEventValueName(raisingExpressionsWithAssignmentMatch.getName()) + " = " + UppaalCodeGenerator.transformExpression(raisingExpressionsWithAssignmentMatch.getValue()));
			}
		}
	}
	
	/**
	 * Ez a metódus hozza létre a parallel regionökben az entry node-ból való kilépéshez szükséges szinkronizációkat.
	 * @throws IncQueryException 
	 * 
	 */
	/*private def createSyncFromEntries(Trace trace) throws IncQueryException {
		var hasSync = new HashMap<State, String>();
		EdgesFromEntryOfParallelRegionsMatcher edgesFromEntryOfParallelRegionsMatcher = trace.engine.getMatcher(EdgesFromEntryOfParallelRegionsQuerySpecification.instance());
		for (EdgesFromEntryOfParallelRegionsMatch edgesFromEntryOfParallelRegionsMatch : edgesFromEntryOfParallelRegionsMatcher.getAllMatches()) {
			if (hasSync.containsKey(edgesFromEntryOfParallelRegionsMatch.getCompositeState())) {
				trace.builder.setEdgeSync(trace.transitionEdgeMap.get(edgesFromEntryOfParallelRegionsMatch.getTransition()),
						hasSync.get(edgesFromEntryOfParallelRegionsMatch.getCompositeState()), false);
			}
			else {
				trace.builder.addGlobalDeclaration("broadcast chan " + trace.syncChanVar + (++trace.syncChanId) + ";");
				hasSync.put(edgesFromEntryOfParallelRegionsMatch.getCompositeState(), trace.syncChanVar + (trace.syncChanId));
				trace.builder.setEdgeSync(trace.transitionEdgeMap.get(edgesFromEntryOfParallelRegionsMatch.getTransition()),
						hasSync.get(edgesFromEntryOfParallelRegionsMatch.getCompositeState()), true);
			}
		}
	}*/
	
	/**
	 * This method creates time-related expressions (after 1 s) on the corresponding edges. Can only transform after .. expressions, and only one unit of measurement (s, ms, stb.) can be used in the entire Yakindu model for proper behaviour.
	 * @throws IncQueryException 
	 */
	private def createTimingEvents(Trace trace) throws IncQueryException {
		val edgesWithTimeTriggerMatcher = trace.engine.getMatcher(EdgesWithTimeTriggerQuerySpecification.instance());
		for (edgesWithTimeTriggerMatch : edgesWithTimeTriggerMatcher.getAllMatches()) {
			trace.builder.setEdgeUpdate(trace.transitionEdgeMap.get(edgesWithTimeTriggerMatch.getIncomingTransition()), trace.clockVar + " = 0");
			trace.builder.setLocationInvariant(trace.stateLocationMap.get(edgesWithTimeTriggerMatch.getSource()), trace.clockVar + " <= " + UppaalCodeGenerator.transformExpression(edgesWithTimeTriggerMatch.getValue()));
			trace.builder.setEdgeGuard(trace.transitionEdgeMap.get(edgesWithTimeTriggerMatch.getTriggerTransition()), trace.clockVar + " >= " + UppaalCodeGenerator.transformExpression(edgesWithTimeTriggerMatch.getValue()));
		}
	}
	
	/**
	 * This method is responsible for creating the control template:
	 * gather all the integer values that can be added as an in-value,
	 * build the loop edges with ! syncs on them
	 *  
	 * @throws Exception 
	 */
	private def void createControlTemplate(Trace trace) throws Exception {
		var controlTemplate = trace.builder.createTemplate("controlTemplate");
		var controlLocation = trace.builder.createLocation("triggerLocation", controlTemplate);
		trace.builder.setInitialLocation(controlLocation, controlTemplate);
		
		val eventsWithTypeMatcher = trace.engine.getMatcher(EventsWithTypeQuerySpecification.instance());
		val inEventsMatcher = trace.engine.getMatcher(InEventsQuerySpecification.instance());
		val inValuesMatcher = trace.engine.getMatcher(InValuesQuerySpecification.instance());
		val inEventValuesMatcher = trace.engine.getMatcher(InEventValuesQuerySpecification.instance());
		// Creating the in-values
		for (eventsWithTypeMatch : eventsWithTypeMatcher.getAllMatches()) {
			if (eventsWithTypeMatch.getEvent().getType().getName() == "integer") {
				trace.builder.addGlobalDeclaration("int " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + ";");
			}
			else if (eventsWithTypeMatch.getEvent().getType().getName() == "boolean") {
				trace.builder.addGlobalDeclaration("bool " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + ";");
			}
		}
		// Creating the ! syncs
		for (inEventsMatch : inEventsMatcher.getAllMatches()) {
			var ownTriggerEdge = trace.builder.createEdge(controlTemplate);
			trace.builder.setEdgeSource(ownTriggerEdge, controlLocation);
			trace.builder.setEdgeTarget(ownTriggerEdge, controlLocation);
			trace.builder.setEdgeSync(ownTriggerEdge, inEventsMatch.getName(), true);
			// If the event is a typed in-event
			if (inEventsMatch.getInEvent().getType() != null && inEventsMatch.getInEvent().getType().getName() == "integer") {
				var updateLocation = trace.builder.createLocation(inEventsMatch.getName() + "_updateLocation", controlTemplate);
				trace.builder.setLocationCommitted(updateLocation);
				// The explicitly declared in values are added to sync
				for (inValuesMatch : inValuesMatcher.getAllMatches()) {		
					createUpdateValueEdge(ownTriggerEdge, updateLocation, controlLocation, inEventsMatch.getName(), inValuesMatch.getInitialValue(), trace);
				}
				// The compared values can be added now to the sync
				for (inEventValuesMatch : inEventValuesMatcher.getAllMatches()) {					
					createUpdateValueEdge(ownTriggerEdge, updateLocation, controlLocation, inEventsMatch.getName(), inEventValuesMatch.getRightOperand(), trace);
				}
			}
		}		
		createTriggers(trace);
	}
	
	/**
	 * This method is responsible for placing the triggers on the mapped edges as synchronizations 
	 * and duplicate edges if the trigger in the Yakindu model is composite.
	 * @throws Exception
	 */
	private def createTriggers(Trace trace) throws Exception {
		val triggeredTransitionMatcher = trace.engine.getMatcher(TriggerOfTransitionQuerySpecification.instance());
		var triggeredTransitions = new HashSet<Transition>();
		var id = 0;
		for (triggerOfTransitionMatch : triggeredTransitionMatcher.getAllMatches()) {	
			// If the mappeed edge already has a trigger, then we clone it, so the next part may not overwrite it
			if (triggeredTransitions.contains(triggerOfTransitionMatch.getTransition())) {
				trace.builder.cloneEdge(trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()));
			}
			// If the mapped edge already has a ! sync, we have to create a syncing location
			if (trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization() != null && trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization().kind == SynchronizationKind.SEND) {
				var syncEdge = createSyncLocation(trace.builder.getEdgeTarget(trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition())), "triggerLocation" + (id += 1), trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization(), trace);
				trace.builder.setEdgeTarget(trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), trace.builder.getEdgeSource(syncEdge));
				trace.builder.setEdgeSync(trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerOfTransitionMatch.getTriggerName(), false);
				triggeredTransitions.add(triggerOfTransitionMatch.getTransition());
				}
			// If no sync, or ? sync 
			else {
				trace.builder.setEdgeSync(trace.transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerOfTransitionMatch.getTriggerName(), false);
				triggeredTransitions.add(triggerOfTransitionMatch.getTransition());
			}
		}
	}	
	
	/**
	 * This method creates and update-edgein the control template so values of in events can be first updated then fired.
	 * @param ownTriggerEdge The Uppaal edge that contains the in event ! synchronization
	 * @param updateLocation The Uppaal location that will be the target of the update edge
	 * @param controlLocation The Uppaal location that will be the source of the update edge
	 * @param inEventName The name of the Yakindu in event with value
	 * @param expression The Uppaal string update expression to be written onto the update edge
	 * @throws IncQueryException
	 */
	private def createUpdateValueEdge(Edge ownTriggerEdge, Location updateLocation, Location controlLocation, String inEventName, Expression expression, Trace trace) throws IncQueryException {
		var controlTemplate = controlLocation.getParentTemplate();
		trace.builder.setEdgeSource(ownTriggerEdge, updateLocation);
		var updateEdge = trace.builder.createEdge(controlTemplate);
		trace.builder.setEdgeSource(updateEdge, controlLocation);
		trace.builder.setEdgeTarget(updateEdge, updateLocation);
		trace.builder.setEdgeUpdate(updateEdge, Helper.getInEventValueName(inEventName) + " = " + UppaalCodeGenerator.transformExpression(expression));
	}
	
	/**
	 * This method creates a new location and a synchronization edge then sets the synchronization of the edge with the given sync.
	 * @param target Uppal location, the target of the new synced edge
	 * @param locationName Name of the new location
	 * @param sync Uppaal synchrnoization to be written onto the edge
	 * @return The recently created synchronization edge
	 * @throws Exception Shows that a ? synch is to be placed on the edge.
	 */
	private def createSyncLocation(Location target, String locationName, Synchronization sync, Trace trace) throws Exception {
		if (sync != null && sync.getKind().getValue() == 0) {
			throw new Exception("A ? snyc is wanted to be placed.");
		}
		var template = target.getParentTemplate();
		var syncLocation = trace.builder.createLocation(locationName, template);
		trace.builder.setLocationCommitted(syncLocation);
		var syncEdge = trace.builder.createEdge(template);
		trace.builder.setEdgeSource(syncEdge, syncLocation);
		trace.builder.setEdgeTarget(syncEdge, target);
		trace.builder.setEdgeSync(syncEdge, sync);
		return syncEdge;
	}	
	
	/**
	 * This method creates a new location and a synchronization edge then sets the synchronization of the edge with the given sync.
	 * @param target Uppal location, the target of the new synced edge
	 * @param locationName Name of the new location
	 * @param sync Uppaal synchrnoization to be written onto the edge
	 * @return The recently created synchronization edge
	 * @throws Exception Shows that a ? synch is to be placed on the edge.
	 */
	private def createSyncLocationWithString(Location target, String locationName, String sync, Trace trace) throws Exception {
		var template = target.getParentTemplate();
		var syncLocation = trace.builder.createLocation(locationName, template);
		trace.builder.setLocationCommitted(syncLocation);
		var syncEdge = trace.builder.createEdge(template);
		trace.builder.setEdgeSource(syncEdge, syncLocation);		
		trace.builder.setEdgeTarget(syncEdge, target);
		trace.builder.setEdgeSync(syncEdge, sync, true);
		return syncEdge;
	}	
	
}