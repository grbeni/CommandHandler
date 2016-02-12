package inc;

import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelBuilder;
import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelSaver;
import hu.bme.mit.inf.alf.uppaal.transformation.serialization.UppaalModelSerializer;
import inc.util.ChoicesQuerySpecification;
import inc.util.CompositeStatesQuerySpecification;
import inc.util.EdgesAcrossRegionsQuerySpecification;
import inc.util.EdgesFromEntryOfParallelRegionsQuerySpecification;
import inc.util.EdgesInSameRegionQuerySpecification;
import inc.util.EdgesWithEffectQuerySpecification;
import inc.util.EdgesWithGuardQuerySpecification;
import inc.util.EdgesWithRaisingEventQuerySpecification;
import inc.util.EdgesWithTimeTriggerQuerySpecification;
import inc.util.EntryOfRegionsQuerySpecification;
import inc.util.EventsQuerySpecification;
import inc.util.EventsWithTypeQuerySpecification;
import inc.util.ExitNodeSyncQuerySpecification;
import inc.util.ExitNodesQuerySpecification;
import inc.util.FinalStateEdgeQuerySpecification;
import inc.util.FinalStatesQuerySpecification;
import inc.util.InEventValuesQuerySpecification;
import inc.util.InEventsQuerySpecification;
import inc.util.InValuesQuerySpecification;
import inc.util.LocalReactionValueOfEffectQuerySpecification;
import inc.util.RaisingExpressionsWithAssignmentQuerySpecification;
import inc.util.SourceAndTargetOfTransitionsQuerySpecification;
import inc.util.StatesQuerySpecification;
import inc.util.TriggerOfTransitionQuerySpecification;
import inc.util.VariableDefinitionsQuerySpecification;
import inc.util.VerticesOfRegionsQuerySpecification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.incquery.runtime.api.IncQueryEngine;
import org.eclipse.incquery.runtime.api.impl.RunOnceQueryEngine;
import org.eclipse.incquery.runtime.exception.IncQueryException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.yakindu.base.expressions.expressions.Expression;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.State;
import org.yakindu.sct.model.sgraph.Statechart;
import org.yakindu.sct.model.sgraph.Transition;
import org.yakindu.sct.model.sgraph.Vertex;
import org.yakindu.sct.model.stext.stext.EventRaisingExpression;
import org.yakindu.sct.model.stext.stext.LocalReaction;

import de.uni_paderborn.uppaal.templates.Edge;
import de.uni_paderborn.uppaal.templates.Location;
import de.uni_paderborn.uppaal.templates.Synchronization;
import de.uni_paderborn.uppaal.templates.Template;

/**
 * Az osztály, amely a Yakindu példánymodell alapján létrehozza az UPPAAL példánymodellt.
 * Függ a PatternMatcher és az UppaalModelBuilder osztályoktól.
 * @author Graics Bence
 * 
 * Location-ök:
 *  - vertexekbõl
 *  - state entry trigger esetén committed location: entryOfB-> nincs hozzá vertex
 * Edge-ek:
 *  - transition-ökbõl
 *  - composite state belépõ élei: legfelsõ ! él, eggyel alatta ? élek isValidVar + " = true" -val
 *  - composite state kilépõ élei: legfelsõ ! él, minden alatta lévõ régióban ? élek isValidVar + " = false" -szal
 *  - különbözõ absztrakciós szinteket összekötõ élek
 */

public class CommandHandler extends AbstractHandler {
	
	// IncQuery engines
	private IncQueryEngine engine;
	private RunOnceQueryEngine runOnceEngine;
	 
	// Uppaal variable namse
	private final String syncChanVar = "syncChan";
	private final String isActiveVar = "isActive";
	private final String clockVar = "Timer";
	private final String endVar = "end";
			
	// For the building of the Uppaal model
	private UppaalModelBuilder builder = null;	
			
	// A Map for Yakindu:Region -> UPPAAL:Template mapping									 								
	private Map<Region, Template> regionTemplateMap = null;
			
	// A Map for Yakindu:Vertex -> UPPAAL:Location mapping									 								
	private Map<Vertex, Location> stateLocationMap = null;
			
	// A Map for Yakindu:Transition -> UPPAAL:Edge mapping
	private Map<Transition, Edge> transitionEdgeMap = null;
			
	// A Map for Yakindu:Vertex -> UPPAAL:Edge mapping
	// Returns the Uppaal edge going to the location equivalent of the Yakindu vertex going from the entryLocation
	// (Entry event or composite states.)
	private Map<Vertex, Edge> hasEntryLoc = null;

	// A Map containg the outgoing edge of the trigger location
	private Map<Transition, Edge> hasTriggerPlusEdge = null;
	
	// A Map containg the generated init location of a template
	private Map<Template, Location> hasInitLoc = null;
			
	// For the generation of sync channels
	private int syncChanId = 0;
	// For the generation of entry locations
	private int entryStateId = 0;
	// For the generation of raising locations
	private int raiseId = 0;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ISelection sel = HandlerUtil.getActiveMenuSelection(event);
		try {
			if (sel instanceof IStructuredSelection) {
				IStructuredSelection selection = (IStructuredSelection) sel;
				if (selection.getFirstElement() != null) {
					if (selection.getFirstElement() instanceof IFile) {
						IFile file = (IFile) selection.getFirstElement();
						ResourceSet resSet = new ResourceSetImpl();
						URI fileURI = URI.createPlatformResourceURI(file
								.getFullPath().toString(), true);
						Resource resource;
						try {
							resource = resSet.getResource(fileURI, true);
						} catch (RuntimeException e) {
							return null;
						}

						if (resource.getContents() != null) {
							if (resource.getContents().get(0) instanceof Statechart) {
								String fileURISubstring = file.getLocationURI().toString().substring(5);

								Statechart statechart = (Statechart) resource.getContents().get(0);

								engine = IncQueryEngine.on(resource);
								runOnceEngine = new RunOnceQueryEngine(resource);
								Helper.setEngine(engine, runOnceEngine);
								
								try {
									// UPPAAL model initialization
									builder = UppaalModelBuilder.getInstance();
									builder.createNTA(statechart.getName());
									
									// Map initialization
									regionTemplateMap = new HashMap<Region, Template>();									 								
									stateLocationMap = new HashMap<Vertex, Location>();
									transitionEdgeMap = new HashMap<Transition, Edge>();
									hasEntryLoc = new HashMap<Vertex, Edge>();
									hasTriggerPlusEdge = new HashMap<Transition, Edge>();
									hasInitLoc = new HashMap<Template, Location>();
									
									// ID variables reset
									syncChanId = 0;
									entryStateId = 0;
									raiseId = 0;
									
									// We only add end variable if there is a final state in the Yakindu model
									if (Helper.hasFinalState()) {
										builder.addGlobalDeclaration("bool " + endVar + " = false;" );
									}
									
									// Creation of integer and boolean variables 
									createVariables();
									
									// Creation of templates from regions 
									createTemplates();	
									
									// Creating the control template so we can trigger in events (with values)
									createControlTemplate();																											
									
									// Builds the Uppaal model from the elements added above
									builder.buildModel();

									// Crates an Uppaal model editable by SampleRefelcetiveEcoreEditor
									builder.saveUppaalModel(fileURISubstring);									
									
									String filen = UppaalModelSaver.removeFileExtension(fileURISubstring);									
									// Saves the model to an XML file editable by Uppaal
									UppaalModelSerializer.saveToXML(filen);
									// Creates the q file-t
									UppaalQueryGenerator.saveToQ(filen);
									
									// Resets the builder, so the next transformation begins with an empty model
									builder.reset();

								} catch (IncQueryException e) {
									e.printStackTrace();
								}
							}
						}
						return null;
					}
				}
			}
		} catch (Exception exception) {
			exception.printStackTrace();
		}
		return null;
	}	

	/**
	 * This method creates Uppaal global variables based on Yakindu internal and interface variables.
	 * Only handles integer and boolean types.
	 * @throws IncQueryException
	 */
	private void createVariables() throws IncQueryException {
		VariableDefinitionsMatcher variableDefinitionsMatcher = engine.getMatcher(VariableDefinitionsQuerySpecification.instance());
		Collection<VariableDefinitionsMatch> allVariableDefinitions = variableDefinitionsMatcher.getAllMatches();
		System.out.println("Number of variables: " + allVariableDefinitions.size());
		for (VariableDefinitionsMatch variableMatch : allVariableDefinitions) {
			StringBuilder expression = new StringBuilder();
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
				builder.addGlobalDeclaration(expression.toString());
			}
			else {
				builder.addGlobalDeclaration(expression.append("=" + UppaalCodeGenerator.transformExpression(variableMatch.getVariable().getInitialValue()) + ";").toString());
			}			
		}
	}
	
	/**
	 * This method creates Uppaal templates based on Yakindu regions.
	 * @throws Exception 
	 */
	private void createTemplates() throws Exception {
		EntryOfRegionsMatcher entryOfRegionsMatcher = engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		// Iterating through regions, and creating the Uppaal elements "matching" the Yakindu elements.
		for (EntryOfRegionsMatch regionMatch : entryOfRegionsMatcher.getAllMatches()) {
			Template template = builder.createTemplate(Helper.getTemplateNameFromRegionName(regionMatch.getRegion())); // We name the templates
			// Setting the activity variables of templates
			if (Helper.isTopRegion(regionMatch.getRegion())) {
				builder.addLocalDeclaration("bool " + isActiveVar + " = true;", template);
			} 
			else {
				builder.addLocalDeclaration("bool " + isActiveVar + " = false;", template);
			}			
			// Creating a clock for each template
			builder.addLocalDeclaration("clock " + clockVar + ";", template);
			
			// The region-template pairs are put into the Map
			regionTemplateMap.put(regionMatch.getRegion(), template);
										   									
			// Setting the initial locations (this will change, if the template is not top region equivalent)						 
			Location entryLocation = builder.createLocation(regionMatch.getEntry().getKind().getName(), template);
			builder.setInitialLocation(entryLocation, template);
			// The entry node is committed
			builder.setLocationCommitted(entryLocation);
			builder.setLocationComment(entryLocation, "Initial entry node");
	
			// Putting the entry into the map								 
			stateLocationMap.put(regionMatch.getEntry(), entryLocation);
		
			// Creating locations from states
			createLocationsFromStates(regionMatch.getRegion(), template);
			
			// Creating locations from choices
			createLocationsFromChoices(regionMatch.getRegion(), template);
			
			// Creating locations from final states
			createLocationsFromFinalStates(regionMatch.getRegion(), template);
			
			// Creating locations from exit nodes
			createLocationsFromExitNodes(regionMatch.getRegion(), template);			
			
			// Creating edges from same region transitions																		
			createEdges(regionMatch.getRegion(), template);			
		}			
		
		// Creating entry location for each composite state
		createEntryForCompositeStates();
		
		// Creating entry location for each state with entry event
		setEntryEdgeUpdatesFromStates();
		
		// Setting the entry edges of composite states, so every time we enter the state ordinarily (from the same template), all the subtemplates are set properly (isActive = true)
		createEntryEdgesForAbstractionLevels();
		
		// Setting the exit edges of composite states, so every time we leave the state ordinarily (to the same template), all the subtemplates are set properly (isActive = false)
		createExitEdgesForAbstractionLevels();
		
		// Setting the not ordinary (across templates) synchronizations.
		createEdgesForDifferentAbstraction();
		
		// Creating the synchronizations of exit nodes
		createUpdatesForExitNodes();
		
		// Setting the updates of incoming edges of final states
		createFinalStateEdgeUpdates();				

		// Creating edge effects
		setEdgeUpdates();
		
		// Creating edge guards
		setEdgeGuards();		
		
		// Create events as broadcast channels
		createEvents();
		
		// Create loop edges + raising locations as local reactions
		createLocalReactions();
		
		// Creating template guards on every edge: isActive
		createTemplateValidityGuards();
		
		// Entry kimenõ élek beSyncelése
		// Sajnos nem mûködik ebben a forméban, hogy egyszerre jöjjenek ki az entry node-ból :(
		//createSyncFromEntries();
		
		// Transforming after .. expression
		createTimingEvents();
	}
	
	/**
	 * This method creates Uppaal locations based on Yakindu states.
	 * @param region Yakindu region whose states we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private void createLocationsFromStates(Region region, Template template) throws IncQueryException {
		StatesMatcher statesMatcher = engine.getMatcher(StatesQuerySpecification.instance());
		for (StatesMatch stateMatch : statesMatcher.getAllMatches(null, region, null)) {												
			Location aLocation = builder.createLocation(stateMatch.getName(), template);
			stateLocationMap.put(stateMatch.getState(), aLocation); // Putting the state-location pairs into the map
			if (Helper.isCompositeState(stateMatch.getState())) {
				builder.setLocationComment(aLocation, "Composite state");
			}
			else {
				builder.setLocationComment(aLocation, "Simple state");
			}												
		}
	}
	
	/**
	 * This method creates Uppaal (committed) locations based on Yakindu choices.
	 * @param region Yakindu region whose choices we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private void createLocationsFromChoices(Region region, Template template) throws IncQueryException {
		// To guarantee unique names
		int id = 0; 
		ChoicesMatcher choicesMatcher = engine.getMatcher(ChoicesQuerySpecification.instance());
		for (ChoicesMatch choiceMatch : choicesMatcher.getAllMatches(null, region)) {				
			Location aLocation = builder.createLocation("Choice" + id++, template);
			builder.setLocationCommitted(aLocation);
			stateLocationMap.put(choiceMatch.getChoice(), aLocation); // Putting the choice-location pairs into the map	
			builder.setLocationComment(aLocation, "A choice");
		}
	}
	
	/**
	 * This method creates Uppaal locations based on Yakindu final states.
	 * @param region Yakindu region whose final states we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private void createLocationsFromFinalStates(Region region, Template template) throws IncQueryException {
		// To guarantee unique names
		int id = 0; 
		FinalStatesMatcher finalStatesMatcher = engine.getMatcher(FinalStatesQuerySpecification.instance());
		for (FinalStatesMatch finalStateMatch : finalStatesMatcher.getAllMatches(null, region)) {										
			Location aLocation = builder.createLocation("FinalState" + id++, template);
			stateLocationMap.put(finalStateMatch.getFinalState(), aLocation); // Putting the  final state-location pairs into the map		
			builder.setLocationComment(aLocation, "A final state");
		}
	}
	
	/**
	 * This method creates end = false update on each incoming edge of the final state locations.
	 * @throws IncQueryException
	 */
	private void createFinalStateEdgeUpdates() throws IncQueryException {
		FinalStateEdgeMatcher finalStateEdgeMatcher = engine.getMatcher(FinalStateEdgeQuerySpecification.instance());
		for (FinalStateEdgeMatch finalStateEdgeMatch : finalStateEdgeMatcher.getAllMatches()) {
			builder.setEdgeUpdate(transitionEdgeMap.get(finalStateEdgeMatch.getIncomingEdge()), endVar + " = true");
		}
	}
	
	/**
	 * This method creates Uppaal locations based on Yakindu exit nodes.
	 * @param region Yakindu region whose final states we want to process
	 * @param template Uppaal template this method puts the locations into
	 * @throws IncQueryException
	 */
	private void createLocationsFromExitNodes(Region region, Template template) throws IncQueryException {
		// To guarantee unique names
		int id = 0; 
		ExitNodesMatcher exitNodesMatcher = engine.getMatcher(ExitNodesQuerySpecification.instance());
		for (ExitNodesMatch exitNodesMatch : exitNodesMatcher.getAllMatches(null, region)) {
			Location exitNode = builder.createLocation("ExitNode" + (id++), template);
			stateLocationMap.put(exitNodesMatch.getExit(), exitNode); // Putting the  exit node-location pairs into the map		
			builder.setLocationComment(exitNode, "An exit node");
		}
	}
	
	/**
	 * This method creates ! synchronizations on incoming edges of exit nodes, and ? synchronization on the default transition of the composite state.
	 * @throws Exception 
	 */
	private void createUpdatesForExitNodes() throws Exception {
		int id = 0;
		ExitNodeSyncMatcher exitNodeSyncMatcher = engine.getMatcher(ExitNodeSyncQuerySpecification.instance());
		for (ExitNodeSyncMatch exitNodesMatch : exitNodeSyncMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge exitNodeEdge = transitionEdgeMap.get(exitNodesMatch.getExitNodeTransition());
			builder.setEdgeSync(exitNodeEdge, syncChanVar + (syncChanId), true);
			if (transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()).getSynchronization() != null ) {
				if (transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()).getSynchronization().getKind().getValue() == 0) {
					throw new Exception("? sync on default edge of exit node");
				}				
				Edge syncEdge = createSyncLocation(builder.getEdgeTarget(transitionEdgeMap.get(exitNodesMatch.getDefaultTransition())), "exitNodeSyncLoc" + (id++), transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()).getSynchronization());
				builder.setEdgeTarget(transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()), builder.getEdgeSource(syncEdge));				
			}
			builder.setEdgeSync(transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()), syncChanVar + (syncChanId), false);
			// The subregions should not be prohibitied, as the outgoing edge of the composite state location automatically does it
		}
	}
	
	/**
	 * This method creates Uppaal edges based on Yakindu transitions whose source and target are in the same region.
	 * @param region Yakindu region whose transitions we want to process
	 * @param template Uppaal template this method puts the edges into
	 * @throws Exception
	 */
	private void createEdges(Region region, Template template) throws Exception {
		EdgesInSameRegionMatcher edgesInSameRegionMatcher = engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());
		for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, null, region)) {											
			// If both the source and target are in the given region
			if (!(stateLocationMap.containsKey(edgesInSameRegionMatch.getSource()) && stateLocationMap.containsKey(edgesInSameRegionMatch.getTarget()))) {								
				throw new Exception("The source or the target is null.");
			}
			Edge anEdge = builder.createEdge(template);
			anEdge.setSource(stateLocationMap.get(edgesInSameRegionMatch.getSource()));
			anEdge.setTarget(stateLocationMap.get(edgesInSameRegionMatch.getTarget()));
			transitionEdgeMap.put(edgesInSameRegionMatch.getTransition(), anEdge); // Putting the  transition-edge pairs into the map							
		}
	}
	
	/**
	 * This method creates the entry location of composite states, so subregion activation may take place at every ordinary enter.
	 * @throws Exception 
	 */
	private void createEntryForCompositeStates() throws Exception {
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {				
			// Create entry location
			Location stateEntryLocation = createEntryLocation(compositeStateMatch.getCompositeState(), compositeStateMatch.getParentRegion());
			// Set the targets of each incoming edge to the entry location
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, null, compositeStateMatch.getCompositeState())) {
				// Only same region edges
				if ((transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition()))) {
					transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).setTarget(stateEntryLocation);
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
	private Location createEntryLocation(Vertex vertex, Region region) {
		Location stateEntryLocation = builder.createLocation("EntryLocationOf" + vertex.getName() + (entryStateId++), regionTemplateMap.get(region));
		builder.setLocationCommitted(stateEntryLocation);
		Edge entryEdge = builder.createEdge(regionTemplateMap.get(region));
		builder.setEdgeSource(entryEdge, stateEntryLocation);
		builder.setEdgeTarget(entryEdge, stateLocationMap.get(vertex));
		hasEntryLoc.put(vertex, entryEdge); // Putting the  vertex-edge pairs into the map		
		return stateEntryLocation;
	}
	
	/**
	 * This method creates ! sync on each entry location edge of composite states, and ? syncs in each subregion of composite states.
	 * @throws Exception 
	 */
	private void createEntryEdgesForAbstractionLevels() throws Exception {
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			// Entry location edges must already be created
			Edge entryEdge = hasEntryLoc.get(compositeStateMatch.getCompositeState());
			builder.setEdgeSync(entryEdge, syncChanVar + (syncChanId), true);
			for (Region subregion : compositeStateMatch.getCompositeState().getRegions()) {
				generateNewInitLocation(subregion);
			}
			// Create ? syncs in all subregions
			setAllRegionsWithSync(true, compositeStateMatch.getCompositeState().getRegions());			
		}
	}
	
	/**
	 * This method creates ! sync on each outgoing edge of composite states, and ? syncs in each region whose ancestor is the composite states.
	 * @throws Exception 
	 */
	private void createExitEdgesForAbstractionLevels() throws Exception {
		int id = 0;
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			// Each outgoing edge must be updated by the exiting sync
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, compositeStateMatch.getCompositeState(), null)) {				
				if (transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition())) { // So we investigate only same region edges
					if (transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).getSynchronization() == null) {
						builder.setEdgeSync(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), syncChanVar + (syncChanId), true);
					}
					// If the outgoing edge already has a sync, a syncing location is created
					else {
						Edge syncEdge = createSyncLocation(stateLocationMap.get(sourceAndTargetOfTransitionsMatch.getTarget()), "CompositeSyncLocation" + (++id), null);
						builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), true);
						builder.setEdgeTarget(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), builder.getEdgeSource(syncEdge));
					}	
				}
			}
			// Disabling all regions below it
			List<Region> subregionList = new ArrayList<Region>();
			Helper.addAllSubregionsToRegionList(compositeStateMatch.getCompositeState(), subregionList);
			setAllRegionsWithSync(false, subregionList);			
		}
	}
	
	/**
	 * Creates ? sync edges and isActive updates in all regions given in the list. These edges may lead to the init location or to their sources.
	 * @param toBeTrue True if region activation is needed, false if region disabling is needed
	 * @param regionList List of Yakindu regions that need to be synced
	 * @throws Exception 
	 */
	private void setAllRegionsWithSync(boolean toBeTrue, List<Region> regionList) throws Exception {
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		for (Region subregion : regionList) {	
			for (VerticesOfRegionsMatch verticesOfRegionMatch : verticesOfRegionsMatcher.getAllMatches(subregion, null)) {
				// No loop edge from choice or entry locations
				if (!(Helper.isChoice(verticesOfRegionMatch.getVertex())) && !(Helper.isEntry(verticesOfRegionMatch.getVertex()))) {
					Edge syncEdge = builder.createEdge(regionTemplateMap.get(subregion));
					builder.setEdgeSync(syncEdge, syncChanVar + syncChanId, false);
					builder.setEdgeUpdate(syncEdge, isActiveVar + " = " + ((toBeTrue) ? "true" : "false"));
					builder.setEdgeSource(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
					// In case of entry, we investigate where the sync edge must be connected depending on history indicators
					if (toBeTrue) {
						if (Helper.hasHistory(subregion)) {
							if (hasEntryLoc.containsKey(verticesOfRegionMatch.getVertex())) {
								builder.setEdgeTarget(syncEdge, hasEntryLoc.get(verticesOfRegionMatch.getVertex()).getSource());
							}
							else {
								builder.setEdgeTarget(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
							}
						}
						else {
							builder.setEdgeTarget(syncEdge, stateLocationMap.get(Helper.getEntryOfRegion(subregion)));
						}
					}
					// In case of exit the sync edge must be a loop edge
					else {
						builder.setEdgeTarget(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
						setHelperEdgeExitEvent(syncEdge, verticesOfRegionMatch.getVertex());
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
	private void generateNewInitLocation(Region region) throws IncQueryException {
		Location initLocation = builder.createLocation("GeneratedInit", regionTemplateMap.get(region));
		Edge syncEdge = builder.createEdge(regionTemplateMap.get(region));
		builder.setEdgeSync(syncEdge, syncChanVar + syncChanId, false);
		builder.setEdgeUpdate(syncEdge, isActiveVar + " = true");
		builder.setEdgeSource(syncEdge, initLocation);
		builder.setEdgeTarget(syncEdge, stateLocationMap.get(Helper.getEntryOfRegion(region)));
		builder.setInitialLocation(initLocation, regionTemplateMap.get(region));
		hasInitLoc.put(regionTemplateMap.get(region), initLocation);
	}
	
	/**
	 * Creates edges whose source and target are not in the same region.
	 * @throws Exception 
	 */
	private void createEdgesForDifferentAbstraction() throws Exception {
		EdgesAcrossRegionsMatcher edgesAcrossRegionsMatcher = engine.getMatcher(EdgesAcrossRegionsQuerySpecification.instance());
		for (EdgesAcrossRegionsMatch acrossTransitionMatch : edgesAcrossRegionsMatcher.getAllMatches()) {											
			// The level of both endpoints must be investigated and further action must be taken accordingly
			if (!(stateLocationMap.containsKey(acrossTransitionMatch.getSource()) && stateLocationMap.containsKey(acrossTransitionMatch.getTarget()))) {								
				throw new Exception("The target or the source is not mapped: " + acrossTransitionMatch.getSource() + " " + stateLocationMap.containsKey(acrossTransitionMatch.getTarget()));				
			}
			int sourceLevel = Helper.getLevelOfVertex(acrossTransitionMatch.getSource());
			int targetLevel = Helper.getLevelOfVertex(acrossTransitionMatch.getTarget());
			if (sourceLevel < targetLevel) {
				createEdgesWhenSourceLesser(acrossTransitionMatch.getSource(), acrossTransitionMatch.getTarget(), acrossTransitionMatch.getTransition(), targetLevel, targetLevel - sourceLevel, new ArrayList<Region>());							
			}						
			if (sourceLevel > targetLevel) {
				createEdgesWhenSourceGreater(acrossTransitionMatch.getSource(), acrossTransitionMatch.getTarget(), acrossTransitionMatch.getTransition(), sourceLevel, new ArrayList<Region>());
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
	private void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions) throws Exception {
		// Recursion:
		// Going back to the top level, and from there on each level syncs are created
		if (source.getParentRegion() != target.getParentRegion()) {
			visitedRegions.add(target.getParentRegion());
			createEdgesWhenSourceLesser(source, (Vertex) target.getParentRegion().getComposite(), transition, lastLevel, levelDifference, visitedRegions);
		}
		// If top level is reached:
		if (source.getParentRegion() == target.getParentRegion()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";"); // New sync variable is created
			// Edge is created with the new sync on it
			Edge abstractionEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(abstractionEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(abstractionEdge, stateLocationMap.get(target));
			builder.setEdgeSync(abstractionEdge, syncChanVar + (syncChanId), true);
			builder.setEdgeComment(abstractionEdge, "In Yakindu this edge leads to a vertex in another region. (Lower abstraction)");
			// If the target has entry event, it must be written onto the edge
			setHelperEdgeEntryEvent(abstractionEdge, target, lastLevel); 
			// This edge is the mapped edge of the across region transition	
			transitionEdgeMap.put(transition, abstractionEdge);
			// If the target is a composite state, we enter all its subregions except the visited ones
			setEdgeEntryAllSubregions(target, visitedRegions);
			// If the source is a composite state, all subregions must be disabled
			if (Helper.isCompositeState(source)) {
				setEdgeExitAllSubregions(source, new ArrayList<Region>());
			}
		}	
		// If we are not in top region, ? synced edges are created from EVERY location and connected to the target (so enter is possible no matter the history)
		else {
			VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
			for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches(target.getParentRegion(), null)) {				
				setHelperEdges(stateLocationMap.get(verticesOfRegionsMatch.getVertex()), target, lastLevel);
			}
			// Subtemplate's "initial location" is connected to the right location
			if (hasInitLoc.containsKey(regionTemplateMap.get(target.getParentRegion()))) {
				setHelperEdges(hasInitLoc.get(regionTemplateMap.get(target.getParentRegion())), target, lastLevel);				
			}
			// If the target is a composite state, we enter all its regions apart from this one
			// Except if it is the last level: then we enter the state ordinarily
			if (lastLevel != Helper.getLevelOfVertex(target) && Helper.isCompositeState(target)) {
				setEdgeEntryAllSubregions(target, visitedRegions);
			}
		}		
	}
	
	/**
	 * This method creates a synchrnoizations from the generated init location to the first normal state.
	 * @param visitedRegions Those Yakindu regions whose template equivalents we want to create the channels in.
	 * @throws Exception
	 */
	private void setSyncFromGeneratedInit(List<Region> visitedRegions) throws Exception {
		for (Region subregion: visitedRegions) {
			if (!(hasInitLoc.containsKey(regionTemplateMap.get(subregion)))) {
				throw new Exception("No initial location: " + subregion.getName());
			}
			else {
				Edge fromGeneratedInit = builder.createEdge(regionTemplateMap.get(subregion));
				builder.setEdgeSource(fromGeneratedInit, hasInitLoc.get(regionTemplateMap.get(subregion)));
				builder.setEdgeTarget(fromGeneratedInit, stateLocationMap.get(Helper.getEntryOfRegion(subregion)));
				builder.setEdgeSync(fromGeneratedInit, syncChanVar + syncChanId, false);
				builder.setEdgeUpdate(fromGeneratedInit, isActiveVar + " = true");
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
	private void setHelperEdges(Location source, Vertex target, int lastLevel) throws IncQueryException {
		Edge syncEdge = builder.createEdge(source.getParentTemplate());					
		builder.setEdgeSource(syncEdge, source);		
		// On the last level if the target has an entry location, the edge must be connected to it
		if (lastLevel == Helper.getLevelOfVertex(target) && hasEntryLoc.containsKey(target)) {
			builder.setEdgeTarget(syncEdge, builder.getEdgeSource(hasEntryLoc.get(target)));
		}							
		// On other levels it MUST NOT be connected to the entry location for it might spoil the whole mappping
		else {					
			builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));
		}
		// If the target has an entry event, it must be written onto the edge
		setHelperEdgeEntryEvent(syncEdge, target, lastLevel);
		builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), false);
		builder.setEdgeUpdate(syncEdge, isActiveVar + " = true");
	}
	
	/**
	 * This method writes the entry event of the given target onto the given edge
	 * @param edge Uppaal edge
	 * @param target Yakindu target whose Uppaal equivalent the edge will be connected to
	 * @param lastLevel Integer indicating the lowest abstraction level in the given mapping
	 * @throws IncQueryException
	 */	
	private void setHelperEdgeEntryEvent(Edge edge, Vertex target, int lastLevel) throws IncQueryException {
		if (Helper.hasEntryEvent(target) && lastLevel != Helper.getLevelOfVertex(target)) {
			for (StatesWithEntryEventMatch statesWithEntryEventMatch : runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
				if (statesWithEntryEventMatch.getState() == target) {
					String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
					builder.setEdgeUpdate(edge, effect);
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
	private void setEdgeEntryAllSubregions(Vertex target, List<Region> visitedRegions) throws Exception {		
		List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions());
		pickedSubregions.removeAll(visitedRegions);
		setAllRegionsWithSync(true, pickedSubregions);		
		setSyncFromGeneratedInit(pickedSubregions);
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
	private void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel, List<Region> visitedRegions) throws Exception {
		// On the lowest level a loop edge is created
		if (Helper.getLevelOfVertex(source) == lastLevel) {
			visitedRegions.add(source.getParentRegion());
			// Creating a sync channel
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), true);
			// Diasabling this region beacause it cannot synchronize to itself
			builder.setEdgeUpdate(ownSyncEdge, isActiveVar + " = false");
			builder.setEdgeComment(ownSyncEdge, "In Yakindu this edge leads to a vertex in another region. (Higher abstraction)");			 
			// This edge is the mapped edge of the across region transition	
			transitionEdgeMap.put(transition, ownSyncEdge);
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel, visitedRegions);
		}
		// The top level
		else if (Helper.getLevelOfVertex(source) == Helper.getLevelOfVertex(target)) {
			// On the top level an edge is created to receive synchronization
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			// If the target has entry loc, that must be the edge targer
			if (hasEntryLoc.containsKey(target)) {
				builder.setEdgeTarget(ownSyncEdge, builder.getEdgeSource(hasEntryLoc.get(target)));
			}
			else {
				builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(target));
			}
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), false);
			// The edge gets an update if the state has exit event
			setHelperEdgeExitEvent(ownSyncEdge, source);
			// All descendant regions are disabled except for the visited ones
			setEdgeExitAllSubregions(source, visitedRegions);
			return;
		}
		// On other levels we create sync edges, disable the region and write exit event on them
		else {
			visitedRegions.add(source.getParentRegion());
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), false);
			builder.setEdgeUpdate(ownSyncEdge, isActiveVar + " = false");
			setHelperEdgeExitEvent(ownSyncEdge, source);
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel, visitedRegions);
		}
	}
	
	/**
	 * This method writes the exit event of the given vertex on the given edge 
	 * @param edge Uppaal edge
	 * @param source Yakindu vertex
	 * @throws IncQueryException
	 */
	private void setHelperEdgeExitEvent(Edge edge, Vertex source) throws IncQueryException {
		if (Helper.hasExitEvent(source)) {
			for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : runOnceEngine.getAllMatches(StatesWithExitEventWithoutOutgoingTransitionMatcher.querySpecification())) {
				if (statesWithExitEventMatch.getState() == source) {
					String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());
					builder.setEdgeUpdate(edge, effect);						
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
	private void setEdgeExitAllSubregions(Vertex source, List<Region> regionsToRemove) throws Exception {
		List<Region> subregionList = new ArrayList<Region>();
		State sourceState = (State) source ;
		Helper.addAllSubregionsToRegionList(sourceState, subregionList);
		subregionList.removeAll(regionsToRemove);
		setAllRegionsWithSync(false, subregionList);
	}
	
	/**
	 * This method creates updates on Uppaal edges based on the Yakindu model.
	 * @throws Exception 
	 */
	private void setEdgeUpdates() throws Exception {
		// Transitions with effects
		EdgesWithEffectMatcher edgesWithEffectMatcher = engine.getMatcher(EdgesWithEffectQuerySpecification.instance());
		for (EdgesWithEffectMatch edgesWithEffectMatch : edgesWithEffectMatcher.getAllMatches()) {
			String effect = UppaalCodeGenerator.transformExpression(edgesWithEffectMatch.getExpression());
			builder.setEdgeUpdate(transitionEdgeMap.get(edgesWithEffectMatch.getTransition()), effect);
		}
		// Creating entry and exit event updates
		setExitEdgeUpdatesFromStates();
		// Creating raising events
		createRaisingEventSyncs();
	}
	
	/**
	 * This method sets updates on outgoing edges of states with exit event.
	 * @throws IncQueryException 
	 */
	private void setExitEdgeUpdatesFromStates() throws IncQueryException {
		for (StatesWithExitEventMatch statesWithExitEventMatch : runOnceEngine.getAllMatches(StatesWithExitEventMatcher.querySpecification())) {
			String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());			
			builder.setEdgeUpdate(transitionEdgeMap.get(statesWithExitEventMatch.getTransition()), effect);			
		}
	}
	
	/**
	 * This method creates entry locations for all states with entry event and writes the update onto the entry edge.
	 * The target of the right same region edges are set to the entry location.
	 * @throws IncQueryException
	 */
	private void setEntryEdgeUpdatesFromStates() throws IncQueryException {
		EdgesInSameRegionMatcher edgesInSameRegionMatcher = engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());		
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
			// Transforming the expression
			String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
			// If it has no entry location yet
			if (!hasEntryLoc.containsKey(statesWithEntryEventMatch.getState())) {
				// Creating the entry location
				Location stateEntryLocation = createEntryLocation(statesWithEntryEventMatch.getState(), statesWithEntryEventMatch.getParentRegion());
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
				// Set the target of the incoming edges
				for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, statesWithEntryEventMatch.getState(), null)) {				
					builder.setEdgeTarget(transitionEdgeMap.get(edgesInSameRegionMatch.getTransition()), stateEntryLocation);				
				}
			}
			// If it already has an entry location
			else {
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
			}
		}
	}
	
	
	/**
	 * This method transforms each local reaction to a loop edge or a circle of two edges with a sync location if a raising expression is present (createRaisingLocationForLocalReaction method).
	 * The source and the target of the loop is the location equivalent of the parent state of the local reaction.
	 * @throws Exception
	 */
	private void createLocalReactions() throws Exception {
		String guard = null;
		// Local reactions with guards only
		for (LocalReactionOnlyGuardMatch localReactionValueOfGuardMatch : runOnceEngine.getAllMatches(LocalReactionOnlyGuardMatcher.querySpecification())) {
			Location stateLocation = stateLocationMap.get(localReactionValueOfGuardMatch.getState());
			Edge localReactionEdge = builder.createEdge(stateLocation.getParentTemplate());
			builder.setEdgeSource(localReactionEdge, stateLocation);
			builder.setEdgeTarget(localReactionEdge, stateLocation);	
			// In case valueof is used in Yakindu local reaction
			if (Helper.isEventName(localReactionValueOfGuardMatch.getName())) {
				guard = Helper.getInEventValueName(localReactionValueOfGuardMatch.getName()) + " " + localReactionValueOfGuardMatch.getOperator().getLiteral() + " " + UppaalCodeGenerator.transformExpression(localReactionValueOfGuardMatch.getGuardRightOperand());
				builder.setEdgeSync(localReactionEdge, localReactionValueOfGuardMatch.getName(), false);
			} 
			else {
				guard = localReactionValueOfGuardMatch.getName() + " " + localReactionValueOfGuardMatch.getOperator().getLiteral() + " " + UppaalCodeGenerator.transformExpression(localReactionValueOfGuardMatch.getGuardRightOperand());				
			}
			builder.setEdgeGuard(localReactionEdge, guard);
			// Raising events
			createRaisingLocationForLocalReaction(localReactionValueOfGuardMatch.getLocalReaction(), localReactionEdge);
		}
		// Local reactions that are not guard only (Trigger / Trigger + Guard
		for (LocalReactionPlainMatch localReactionPlainMatch : runOnceEngine.getAllMatches(LocalReactionPlainMatcher.querySpecification())) {
			Location stateLocation = stateLocationMap.get(localReactionPlainMatch.getState());
			Edge localReactionEdge = builder.createEdge(stateLocation.getParentTemplate());
			builder.setEdgeSource(localReactionEdge, stateLocation);
			builder.setEdgeTarget(localReactionEdge, stateLocation);
			builder.setEdgeSync(localReactionEdge, UppaalCodeGenerator.transformExpression(localReactionPlainMatch.getExpression()), false);
			// Handling the guards
			if (localReactionPlainMatch.getReactionTrigger().getGuard() != null) {
				guard = UppaalCodeGenerator.transformExpression(localReactionPlainMatch.getReactionTrigger().getGuard().getExpression());
				builder.setEdgeGuard(localReactionEdge, guard);
			}
			// Raising events
			createRaisingLocationForLocalReaction(localReactionPlainMatch.getLocalReaction(), localReactionEdge);
		}
		
	}
	
	/**
	 * This method creates a raising sync edge if the local reaction has an event raising expression.
	 * @param localReaction The Yakindu local reaction, that we investigate whether it has an event raising expression
	 * @param localReactionEdge The Uppaal loop edge that contains the local reaction trigger/guard.
	 * @throws Exception
	 */
	private void createRaisingLocationForLocalReaction(LocalReaction localReaction, Edge localReactionEdge) throws Exception {
		LocalReactionValueOfEffectMatcher localReactionValueOfEffectMatcher = engine.getMatcher(LocalReactionValueOfEffectQuerySpecification.instance());
		for (LocalReactionValueOfEffectMatch localReactionValueOfEffectMatch : localReactionValueOfEffectMatcher.getAllMatches(localReaction, null)) {
			builder.setEdgeUpdate(localReactionEdge, UppaalCodeGenerator.transformExpression(localReactionValueOfEffectMatch.getAction()));
			if (localReactionValueOfEffectMatch.getAction() instanceof EventRaisingExpression) {
				EventRaisingExpression eventRaisingExpression = (EventRaisingExpression) localReactionValueOfEffectMatch.getAction();
				Edge syncEdge = createSyncLocationWithString(localReactionEdge.getTarget(), "Raise_" + UppaalCodeGenerator.transformExpression(eventRaisingExpression.getEvent()) + (raiseId++), UppaalCodeGenerator.transformExpression(eventRaisingExpression.getEvent()));
				builder.setEdgeTarget(localReactionEdge, syncEdge.getSource());
				if (eventRaisingExpression.getValue() != null) {
					builder.setEdgeUpdate(localReactionEdge, Helper.getInEventValueName(UppaalCodeGenerator.transformExpression(eventRaisingExpression.getEvent())) + " = " + UppaalCodeGenerator.transformExpression(eventRaisingExpression.getValue()));
				}
			}
		}
	}
	
	/**
	 * This method transforms guards of Yakindu transitions and places them on their Uppaal edge equivalents.
	 * @throws IncQueryException 
	 */
	private void setEdgeGuards() throws IncQueryException {
		EdgesWithGuardMatcher edgesWithGuardMatcher = engine.getMatcher(EdgesWithGuardQuerySpecification.instance());
		EventsWithTypeMatcher eventsWithTypeMatcher = engine.getMatcher(EventsWithTypeQuerySpecification.instance());
		for (EdgesWithGuardMatch edgesWithGuardMatch : edgesWithGuardMatcher.getAllMatches()) {
			// Transforming the guard and placing it onto the edge equivalent
			String guard = " " + UppaalCodeGenerator.transformExpression(edgesWithGuardMatch.getExpression());
			for (EventsWithTypeMatch eventsWithTypeMatch : eventsWithTypeMatcher.getAllMatches()) {
				// If the guard expression cointains an in event variable, then it has to be replaced by the in event variable.
				if (guard.contains(" " + eventsWithTypeMatch.getName() + " ")) {
					guard = guard.replaceAll(" " + eventsWithTypeMatch.getName() + " ", " " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + " ");	
					builder.setEdgeSync(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), eventsWithTypeMatch.getName(), false);
				}
			}
			// So we can concatenate guards
			if (builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != null && builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != "") {
				builder.setEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) + " && " + guard);
			}
			else {					
				builder.setEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), guard);
			}		
		}
	}
	
	/**
	 * This method creates an "isActive" guard on each edge in the Uppaal model, so a transition of a template can only fire if the template is active.
	 * @throws IncQueryException 
	 */
	private void createTemplateValidityGuards() throws IncQueryException {
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches()) {
			// So regularly transformed guards are not deleted
			if (builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != null && builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != "") {
				builder.setEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), ((Helper.hasFinalState()) ? ("!" + endVar + " && ") : "") + isActiveVar + " && " + "(" + builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) + ")");
			} 
			else {
				builder.setEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), ((Helper.hasFinalState()) ? ("!" + endVar + " && ") : "") + isActiveVar);
			}		
		}
	}
	
	/**
	 * Creates events as synchronization channels.
	 * @throws IncQueryException
	 */
	private void createEvents() throws IncQueryException {
		EventsMatcher eventsMatcher = engine.getMatcher(EventsQuerySpecification.instance());
		for (EventsMatch eventsMatch : eventsMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + eventsMatch.getEventName() + ";");
		}
	}
	
	/**
	 * This method creates raise events as another edge synchronizations where it is needed.
	 * (If more than one raising event on an edge, the result will be linked committed locations with synched edges.)
	 * @throws Exception. 
	 */
	private void createRaisingEventSyncs() throws Exception {
		EdgesWithRaisingEventMatcher edgesWithRaisingEventMatcher = engine.getMatcher(EdgesWithRaisingEventQuerySpecification.instance());
		RaisingExpressionsWithAssignmentMatcher raisingExpressionsWithAssignmentMatcher = engine.getMatcher(RaisingExpressionsWithAssignmentQuerySpecification.instance());
		for (EdgesWithRaisingEventMatch edgesWithRaisingEventMatch : edgesWithRaisingEventMatcher.getAllMatches()) {
			Edge raiseEdge = createSyncLocationWithString(transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()).getTarget(), "Raise_" + edgesWithRaisingEventMatch.getName() + (raiseId++), edgesWithRaisingEventMatch.getName());
			builder.setEdgeTarget(transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()), raiseEdge.getSource());
			// Now we apply the updates if the raised event is an in event
			for (RaisingExpressionsWithAssignmentMatch raisingExpressionsWithAssignmentMatch : raisingExpressionsWithAssignmentMatcher.getAllMatches(edgesWithRaisingEventMatch.getTransition(), edgesWithRaisingEventMatch.getElement(), null, null)) {
				 builder.setEdgeUpdate(transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()), Helper.getInEventValueName(raisingExpressionsWithAssignmentMatch.getName()) + " = " + UppaalCodeGenerator.transformExpression(raisingExpressionsWithAssignmentMatch.getValue()));
			}
		}
	}
	
	/**
	 * Ez a metódus hozza létre a parallel regionökben az entry node-ból való kilépéshez szükséges szinkronizációkat.
	 * @throws IncQueryException 
	 * 
	 */
	private void createSyncFromEntries() throws IncQueryException {
		Map<State, String> hasSync = new HashMap<State, String>();
		EdgesFromEntryOfParallelRegionsMatcher edgesFromEntryOfParallelRegionsMatcher = engine.getMatcher(EdgesFromEntryOfParallelRegionsQuerySpecification.instance());
		for (EdgesFromEntryOfParallelRegionsMatch edgesFromEntryOfParallelRegionsMatch : edgesFromEntryOfParallelRegionsMatcher.getAllMatches()) {
			if (hasSync.containsKey(edgesFromEntryOfParallelRegionsMatch.getCompositeState())) {
				builder.setEdgeSync(transitionEdgeMap.get(edgesFromEntryOfParallelRegionsMatch.getTransition()),
						hasSync.get(edgesFromEntryOfParallelRegionsMatch.getCompositeState()), false);
			}
			else {
				builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
				hasSync.put(edgesFromEntryOfParallelRegionsMatch.getCompositeState(), syncChanVar + (syncChanId));
				builder.setEdgeSync(transitionEdgeMap.get(edgesFromEntryOfParallelRegionsMatch.getTransition()),
						hasSync.get(edgesFromEntryOfParallelRegionsMatch.getCompositeState()), true);
			}
		}
	}
	
	/**
	 * This method creates time-related expressions (after 1 s) on the corresponding edges. Can only transform after .. expressions, and only one unit of measurement (s, ms, stb.) can be used in the entire Yakindu model for proper behaviour.
	 * @throws IncQueryException 
	 */
	private void createTimingEvents() throws IncQueryException {
		EdgesWithTimeTriggerMatcher edgesWithTimeTriggerMatcher = engine.getMatcher(EdgesWithTimeTriggerQuerySpecification.instance());
		for (EdgesWithTimeTriggerMatch edgesWithTimeTriggerMatch : edgesWithTimeTriggerMatcher.getAllMatches()) {
			builder.setEdgeUpdate(transitionEdgeMap.get(edgesWithTimeTriggerMatch.getIncomingTransition()), clockVar + " = 0");
			builder.setLocationInvariant(stateLocationMap.get(edgesWithTimeTriggerMatch.getSource()), clockVar + " <= " + UppaalCodeGenerator.transformExpression(edgesWithTimeTriggerMatch.getValue()));
			builder.setEdgeGuard(transitionEdgeMap.get(edgesWithTimeTriggerMatch.getTriggerTransition()), clockVar + " >= " + UppaalCodeGenerator.transformExpression(edgesWithTimeTriggerMatch.getValue()));
		}
	}
	
	/**
	 * This method is responsible for creating the control template:
	 * gather all the integer values that can be added as an in value. 
	 * @throws Exception 
	 */
	private void createControlTemplate() throws Exception {
		Template controlTemplate = builder.createTemplate("controlTemplate");
		Location controlLocation = builder.createLocation("triggerLocation", controlTemplate);
		builder.setInitialLocation(controlLocation, controlTemplate);
		
		EventsWithTypeMatcher eventsWithTypeMatcher = engine.getMatcher(EventsWithTypeQuerySpecification.instance());
		InEventsMatcher inEventsMatcher = engine.getMatcher(InEventsQuerySpecification.instance());
		InValuesMatcher inValuesMatcher = engine.getMatcher(InValuesQuerySpecification.instance());
		InEventValuesMatcher inEventValuesMatcher = engine.getMatcher(InEventValuesQuerySpecification.instance());
		
		for (EventsWithTypeMatch eventsWithTypeMatch : eventsWithTypeMatcher.getAllMatches()) {
			if (eventsWithTypeMatch.getEvent().getType().getName() == "integer") {
				builder.addGlobalDeclaration("int " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + ";");
			}
			else if (eventsWithTypeMatch.getEvent().getType().getName() == "boolean") {
				builder.addGlobalDeclaration("bool " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + ";");
			}
		}
		
		for (InEventsMatch inEventsMatch : inEventsMatcher.getAllMatches()) {
			Edge ownTriggerEdge = builder.createEdge(controlTemplate);
			builder.setEdgeSource(ownTriggerEdge, controlLocation);
			builder.setEdgeTarget(ownTriggerEdge, controlLocation);
			builder.setEdgeSync(ownTriggerEdge, inEventsMatch.getName(), true);
			if (inEventsMatch.getInEvent().getType() != null && inEventsMatch.getInEvent().getType().getName() == "integer") {
				Location updateLocation = builder.createLocation(inEventsMatch.getName() + "_updateLocation", controlTemplate);
				builder.setLocationCommitted(updateLocation);
				for (InValuesMatch inValuesMatch : inValuesMatcher.getAllMatches()) {		
					createUpdateValueEdge(ownTriggerEdge, updateLocation, controlLocation, inEventsMatch.getName(), inValuesMatch.getInitialValue());
				}
				for (InEventValuesMatch inEventValuesMatch : inEventValuesMatcher.getAllMatches()) {					
					createUpdateValueEdge(ownTriggerEdge, updateLocation, controlLocation, inEventsMatch.getName(), inEventValuesMatch.getRightOperand());
				}
			}
		}		
		createTriggers(controlTemplate, controlLocation);
	}
	
	/**
	 * This method is responsible for placing the triggers on the mapped edges as synchronizations 
	 * and duplicate edges if the trigger in the Yakindu model is composite.
	 * @param controlTemplate The control template, we want to handle the triggers from.
	 * @param controlLocation The only location in the control template.
	 * @throws Exception
	 */
	private void createTriggers(Template controlTemplate, Location controlLocation) throws Exception {
		TriggerOfTransitionMatcher transitionMatcher = engine.getMatcher(TriggerOfTransitionQuerySpecification.instance());
		Set<Transition> triggeredTransitions = new HashSet<Transition>();
		int id = 0;
		for (TriggerOfTransitionMatch triggerOfTransitionMatch : transitionMatcher.getAllMatches()) {	
			// If the mappeed edge already has a trigger, then we clone it, so the next part may not overwrite it
			if (triggeredTransitions.contains(triggerOfTransitionMatch.getTransition())) {
				builder.cloneEdge(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()));
			}
			// If the mapped edge already has a sync, we have to create a syncing location
			if (transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization() != null) {
				Edge syncEdge = createSyncLocation(builder.getEdgeTarget(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition())), "triggerLocation" + (++id), transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization());
				builder.setEdgeTarget(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), builder.getEdgeSource(syncEdge));
				builder.setEdgeSync(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerOfTransitionMatch.getTriggerName(), false);
				triggeredTransitions.add(triggerOfTransitionMatch.getTransition());
				hasTriggerPlusEdge.put(triggerOfTransitionMatch.getTransition(), syncEdge);
			}
			else {
				builder.setEdgeSync(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerOfTransitionMatch.getTriggerName(), false);
				triggeredTransitions.add(triggerOfTransitionMatch.getTransition());
			}
		}
	}	
	
	/**
	 * To avoid code duplication.
	 * @param ownTriggerEdge
	 * @param updateLocation
	 * @param controlLocation
	 * @param inEventName
	 * @param expression
	 * @throws IncQueryException
	 */
	private void createUpdateValueEdge(Edge ownTriggerEdge, Location updateLocation, Location controlLocation, String inEventName, Expression expression) throws IncQueryException {
		Template controlTemplate = controlLocation.getParentTemplate();
		builder.setEdgeSource(ownTriggerEdge, updateLocation);
		Edge updateEdge = builder.createEdge(controlTemplate);
		builder.setEdgeSource(updateEdge, controlLocation);
		builder.setEdgeTarget(updateEdge, updateLocation);
		builder.setEdgeUpdate(updateEdge, Helper.getInEventValueName(inEventName) + " = " + UppaalCodeGenerator.transformExpression(expression));
	
	}
	
	/**
	 * Ez a metódus létrehoz egy szinkronizációs élet egy új location-bõl és azt beleköti a targetbe, ráírva a megadott szinkornizációt.
	 * @param target A location, ahova kötni szeretnénk az élet.
	 * @param locationName A név, amelyet adni szeretnénk a létrehozott locationnek.
	 * @param sync A szinkronizáció, amelyet rá szeretnénk tenni az élre.
	 * @param template A template, amelybe bele szeretnénk rakni a létrehozott locationt.
	 * @return A szinkronizáció edge, amely a létrehozott locationbõl belevezet a target locationbe.
	 * @throws Exception Ezt akkor dobja, ha az átadott szinkornizáció ? szinkornizáció. Ekkor a létrehozott struktúra nem mûködhet jól.
	 */
	private Edge createSyncLocation(Location target, String locationName, Synchronization sync) throws Exception {
		if (sync != null && sync.getKind().getValue() == 0) {
			throw new Exception("Egy ? sync-et akar áthelyezni!");
		}
		Template template = target.getParentTemplate();
		Location syncLocation = builder.createLocation(locationName, template);
		builder.setLocationCommitted(syncLocation);
		Edge syncEdge = builder.createEdge(template);
		builder.setEdgeSource(syncEdge, syncLocation);
		builder.setEdgeTarget(syncEdge, target);
		builder.setEdgeSync(syncEdge, sync);
		return syncEdge;
	}	
	
	/**
	 * Ez a metódus létrehoz egy szinkronizációs élet egy új location-bõl és azt beleköti a targetbe, ráírva a megadott szinkornizációt.
	 * @param target A location, ahova kötni szeretnénk az élet.
	 * @param locationName A név, amelyet adni szeretnénk a létrehozott locationnek.
	 * @param sync A szinkronizáció, amelyet rá szeretnénk tenni az élre.
	 * @param template A template, amelybe bele szeretnénk rakni a létrehozott locationt.
	 * @return A szinkronizáció edge, amely a létrehozott locationbõl belevezet a target locationbe.
	 * @throws Exception Ezt akkor dobja, ha az átadott szinkornizáció ? szinkornizáció. Ekkor a létrehozott struktúra nem mûködhet jól.
	 */
	private Edge createSyncLocationWithString(Location target, String locationName, String sync) throws Exception {
		Template template = target.getParentTemplate();
		Location syncLocation = builder.createLocation(locationName, template);
		builder.setLocationCommitted(syncLocation);
		Edge syncEdge = builder.createEdge(template);
		builder.setEdgeSource(syncEdge, syncLocation);		
		builder.setEdgeTarget(syncEdge, target);
		builder.setEdgeSync(syncEdge, sync, true);
		return syncEdge;
	}
	
}
