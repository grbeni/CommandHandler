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
	 
	// Uppaal változónevek
	private final String syncChanVar = "syncChan";
	private final String isActiveVar = "isActive";
	private final String clockVar = "Timer";
	private final String endVar = "end";
			
	// Az UPPAAL modell felépítésre
	private UppaalModelBuilder builder = null;	
			
	// Egy Map a Yakindu:Region -> UPPAAL:Template leképzésre									 								
	private Map<Region, Template> regionTemplateMap = null;
			
	// Egy Map a Yakindu:Vertex -> UPPAAL:Location leképzésre									 								
	private Map<Vertex, Location> stateLocationMap = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge leképzésre
	private Map<Transition, Edge> transitionEdgeMap = null;
			
	// Egy Map a Yakindu:Vertex -> UPPAAL:Edge leképzésre
	// Az entryLocation-nel rendelkezõ vertex Uppaal megfelelõ locationjébe vezetõ élet adja vissza
	private Map<Vertex, Edge> hasEntryLoc = null;
	
	// Egy Map, amely tárolja az egyes Vertexek triggerLocation kimenõ élét
	private Map<Transition, Edge> hasTriggerPlusEdge = null;
	
	// Egy Map, amely tárolja az altemplate-ek "initial location"-jét
	private Map<Template, Location> hasInitLoc = null;
			
	// Szinkronizációs csatornák létrehozására
	private int syncChanId = 0;
	// EntryLoc név generálásra
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
									// UPPAAL modell inicializáció
									builder = UppaalModelBuilder.getInstance();
									builder.createNTA(statechart.getName());
									
									// Map-ek inicializálása
									regionTemplateMap = new HashMap<Region, Template>();									 								
									stateLocationMap = new HashMap<Vertex, Location>();
									transitionEdgeMap = new HashMap<Transition, Edge>();
									hasEntryLoc = new HashMap<Vertex, Edge>();
									hasTriggerPlusEdge = new HashMap<Transition, Edge>();
									hasInitLoc = new HashMap<Template, Location>();
									
									// ID változók resetelése
									syncChanId = 0;
									entryStateId = 0;
									raiseId = 0;
									
									// Csak akkor szennyezzük az Uppaal modellt end változóval, ha van final state a Yakindu modellben
									if (Helper.hasFinalState()) {
										builder.addGlobalDeclaration("bool " + endVar + " = false;" );
									}
									
									// Változók berakása
									createVariables();
									
									// Template-ek létrehozása
									createTemplates();	
									
									// Triggerek felvétele
									createControlTemplate();
																											
									// Felépíti az UPPAAL modellt a berakott elemekbõl
									builder.buildModel();

									// Létrehozza a SampleRefelcetiveEcoreEditorral megnyitható UPPAAL modellt
									builder.saveUppaalModel(fileURISubstring);									
									
									String filen = UppaalModelSaver.removeFileExtension(fileURISubstring);									
									// Elmenti a modellt egy XML fájlba, lényegében létrehozza az UPPAAL által megnyitható fájlt
									UppaalModelSerializer.saveToXML(filen);
									// Létrehozza a q file-t
									UppaalQueryGenerator.saveToQ(filen);
									
									// Reseteli a buildert, hogy a következõ transzformációt nulláról kezdhessük
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
	 * Ez a metódus létrehozza az UPPAAL változókat a Yakindu változók alapján.
	 * @throws IncQueryException
	 */
	private void createVariables() throws IncQueryException {
		// Lekérjük a változó definiciókat
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
	 * Ez a metódus létrehozza az egyes UPPAAL template-eket a Yakindu regionök alapján.
	 * @throws Exception 
	 */
	private void createTemplates() throws Exception {
		// Lekérjük a régiókat
		EntryOfRegionsMatcher entryOfRegionsMatcher = engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		Collection<EntryOfRegionsMatch> regionMatches = entryOfRegionsMatcher.getAllMatches();
		// Végigmegyünk a régiókon, és létrehozzuk a Yakindu modellnek megfeleltethetõ elemeket.
		for (EntryOfRegionsMatch regionMatch : regionMatches) {
			Template template = builder.createTemplate(Helper.getTemplateNameFromRegionName(regionMatch.getRegion()));			
			// Kiszedjük a template nevekbõl a szóközöket, mert az UPPAAL nem szereti
			if (Helper.isTopRegion(regionMatch.getRegion())) {
				// Mégis foglalkozunk, hogy a regionökön átívelõ tranziciók helyes lefutása garantálható legyen
				builder.addLocalDeclaration("bool " + isActiveVar + " = true;", template);
			} 
			else {
				// Az alsóbb szinteken kezdetben false érvényességi változót vezetünk be
				builder.addLocalDeclaration("bool " + isActiveVar + " = false;", template);
			}			
			// Beteszünk egy clockot
			builder.addLocalDeclaration("clock " + clockVar + ";", template);
			
			// A region-template párokat berakjuk a Mapbe
			regionTemplateMap.put(regionMatch.getRegion(), template);
										   									
			//Kiinduló állapotot beállítjuk									 
			Location entryLocation = builder.createLocation(regionMatch.getEntry().getKind().getName(), template);
			builder.setInitialLocation(entryLocation, template);
			// Az entry node committed
			builder.setLocationCommitted(entryLocation);
			builder.setLocationComment(entryLocation, "Initial entry node");
	
			//Betesszük a kezdõállapotot a Map-be									 
			stateLocationMap.put(regionMatch.getEntry(), entryLocation);
		
			// Létrehozzuk a location-öket a state-ekbõl
			createLocationsFromStates(regionMatch.getRegion(), template);
			
			// Létrehozzuk a location-öket a choice-okból
			createLocationsFromChoices(regionMatch.getRegion(), template);
			
			// Létrehozzuk a location-öket a final state-ekbõl
			createLocationsFromFinalStates(regionMatch.getRegion(), template);
			
			// Létrehozzuk a location-öket az exit node-okbõl
			createLocationsFromExitNodes(regionMatch.getRegion(), template);			
			
			// Létrehozzuk az edge-eket a transition-ökbõl																			
			createEdges(regionMatch.getRegion(), template);			
		}	
		
		// Final state bemenõ edge-einek updateinek megadása
		createFinalStateEdgeUpdates();
		
		// Exit node-ok syncjeinek létrehozása
		createUpdatesForExitNodes();
		
		// Composite állapotok entry statejének létrehozása
		createEntryForCompositeStates();
		
		// Beállítjuk a composite state-ek entry transitionjeit, hogy minden bemenetkor minden alrégió helyes állapotba kerüljön (kezdõállapot/önmaga, true)
		createEntryEdgesForAbstractionLevels();
		
		// Beállítjuk a composite state-ek exit transitionjeit, hogy minden kimenetkor minden alrégió helyes állapotba kerüljön (false)
		createExitEdgesForAbstractionLevels();
		
		// Beállítjuk azon csatornákat, amelyek különbözõ absztakciós szintek közötti tranziciókat vezérlik
		createEdgesForDifferentAbstraction();

		// Edge effectek berakása
		setEdgeUpdates();
		
		// Edge guardok berakása
		setEdgeGuards();		
		
		// Create events as broadcast channels
		createEvents();
		
		// Create loop edges + raising locations as local reactions
		createLocalReactions();
		
		// Template guardok berakása
		createTemplateValidityGuards();
		
		// Entry kimenõ élek beSyncelése
		// Sajnos nem mûködik ebben a forméban, hogy egyszerre jöjjenek ki az entry node-ból :(
		//createSyncFromEntries();
		
		// After .. kifejezések transzformálása
		createTimingEvents();
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL locationöket a Yakindu state-ek alapján a StatesMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a state-ek valók.
	 * @param template Az UPPAAL template, amelybe a locationöket kell rakni.
	 * @throws IncQueryException
	 */
	private void createLocationsFromStates(Region region, Template template) throws IncQueryException {
		// Lekérjük az állapotokat
		StatesMatcher statesMatcher = engine.getMatcher(StatesQuerySpecification.instance());
		// Megnézzük a state matcheket és létrehozzuk a location-öket
		for (StatesMatch stateMatch : statesMatcher.getAllMatches(null, region, null)) {												
			Location aLocation = builder.createLocation(stateMatch.getName(), template);
			stateLocationMap.put(stateMatch.getState(), aLocation); // A state-location párokat betesszük a map-be	
			if (Helper.isCompositeState(stateMatch.getState())) {
				builder.setLocationComment(aLocation, "Composite state");
			}
			else {
				builder.setLocationComment(aLocation, "Simple state");
			}												
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL locationöket a Yakindu choice-ok alapján a ChoiceMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a choice-ok valók.
	 * @param template Az UPPAAL template, amelybe a locationöket kell rakni.
	 * @throws IncQueryException
	 */
	private void createLocationsFromChoices(Region region, Template template) throws IncQueryException {
		// A különbözõ choice-ok megkülönböztetésére
		int id = 0; 
		// Lekérjük a choice-okat
		ChoicesMatcher choicesMatcher = engine.getMatcher(ChoicesQuerySpecification.instance());
		// Megnézzük a choice matcheket és létrehozzuk a location-öket		
		for (ChoicesMatch choiceMatch : choicesMatcher.getAllMatches(null, region)) {				
			Location aLocation = builder.createLocation("Choice" + id++, template);
			builder.setLocationCommitted(aLocation);
			stateLocationMap.put(choiceMatch.getChoice(), aLocation); // A choice-location párokat betesszük a map-be	
			builder.setLocationComment(aLocation, "A choice");
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL locationöket a Yakindu final state-ek alapján a FinalStatesMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a final state-ek valók.
	 * @param template Az UPPAAL template, amelybe a locationöket kell rakni.
	 * @throws IncQueryException
	 */
	private void createLocationsFromFinalStates(Region region, Template template) throws IncQueryException {
		// A különbözõ final state-ek megkülönböztetésére
		int id = 0; 
		// Lekérjük a final state-eket
		FinalStatesMatcher finalStatesMatcher = engine.getMatcher(FinalStatesQuerySpecification.instance());
		// Megnézzük a state matcheket és létrehozzuk a location-öket		
		for (FinalStatesMatch finalStateMatch : finalStatesMatcher.getAllMatches(null, region)) {										
			Location aLocation = builder.createLocation("FinalState" + id++, template);
			stateLocationMap.put(finalStateMatch.getFinalState(), aLocation); // A final state-location párokat betesszük a map-be	
			builder.setLocationComment(aLocation, "A final state");
		}
	}
	
	/**
	 * Ez a metódus létrehozza a final state bemenõ élén az end = false update-eket, ami letilt minden tranziciót.
	 * @throws IncQueryException
	 */
	private void createFinalStateEdgeUpdates() throws IncQueryException {
		FinalStateEdgeMatcher finalStateEdgeMatcher = engine.getMatcher(FinalStateEdgeQuerySpecification.instance());
		for (FinalStateEdgeMatch finalStateEdgeMatch : finalStateEdgeMatcher.getAllMatches()) {
			builder.setEdgeUpdate(transitionEdgeMap.get(finalStateEdgeMatch.getIncomingEdge()), endVar + " = true");
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL locationöket a Yakindu exit node-ok alapján az ExitNodesMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a final state-ek valók.
	 * @param template Az UPPAAL template, amelybe a locationöket kell rakni
	 * @throws IncQueryException
	 */
	private void createLocationsFromExitNodes(Region region, Template template) throws IncQueryException {
		// A különbözõ exit node-ok megkülönböztetésére
		int id = 0; 
		// Lekérjük a exit node-okat
		ExitNodesMatcher exitNodesMatcher = engine.getMatcher(ExitNodesQuerySpecification.instance());
		// Megnézzük a state matcheket és létrehozzuk a location-öket		
		for (ExitNodesMatch exitNodesMatch : exitNodesMatcher.getAllMatches(null, region)) {
			// Létrehozunk egy új locationt
			Location exitNode = builder.createLocation("ExitNode" + (id++), template);
			stateLocationMap.put(exitNodesMatch.getExit(), exitNode); // Az exit node-location párokat betesszük a map-be	
			builder.setLocationComment(exitNode, "An exit node");
		}
	}
	
	/**
	 * Ez a metódus felelõs az exit node-okba vezetõ élek broadcast ! szinkronizációjának, és a felette lévõ régiók ? szinkornizációjának létrehozásáért.
	 * @throws IncQueryException
	 */
	private void createUpdatesForExitNodes() throws IncQueryException {
		// Lekérjük az exit node-okat
		ExitNodeSyncMatcher exitNodeSyncMatcher = engine.getMatcher(ExitNodeSyncQuerySpecification.instance());
		for (ExitNodeSyncMatch exitNodesMatch : exitNodeSyncMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge exitNodeEdge = transitionEdgeMap.get(exitNodesMatch.getExitNodeTransition());
			builder.setEdgeSync(exitNodeEdge, syncChanVar + (syncChanId), true);
			builder.setEdgeSync(transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()), syncChanVar + (syncChanId), false);
			//Nem kell letiltani az össezs alatta lévõ regiont, mert azt a kimenõ él automatikusan megcsinálja			
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eket az azonos regionbeli Yakindu transition-ök alapján az EdgesInSameRegionMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a transitionök valók.
	 * @param template Az UPPAAL template, amelybe az edgeket kell rakni.
	 * @throws Exception 
	 */
	private void createEdges(Region region, Template template) throws Exception {
		//Lekérjük a transition match-eket	
		EdgesInSameRegionMatcher edgesInSameRegionMatcher = engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());
		// Megnézzük a transition match-eket és létrehozzuk az edge-eket a megfelelõ guardokkal és effectekkel
		for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, null, region)) {											
			// Ha a két végpont a helyes region-ben van
			if (!(stateLocationMap.containsKey(edgesInSameRegionMatch.getSource()) && stateLocationMap.containsKey(edgesInSameRegionMatch.getTarget()))) {								
				throw new Exception("The source or the target is null.");
			}
			//Létrehozunk egy edge-t
			Edge anEdge = builder.createEdge(template); 
			//Beállítjuk az edge forrását és célját
			anEdge.setSource(stateLocationMap.get(edgesInSameRegionMatch.getSource()));
			anEdge.setTarget(stateLocationMap.get(edgesInSameRegionMatch.getTarget()));
			transitionEdgeMap.put(edgesInSameRegionMatch.getTransition(), anEdge);
							
		}
	}
	
	/**
	 * Ez a metódus hozza létre a composite state-ek entry locationjét. Ez a state-be lépéskor az alrégiókba való lépés miatt szükséges.
	 * (Hogy az minden esetben megvalósuljon.)
	 * @throws Exception 
	 */
	private void createEntryForCompositeStates() throws Exception {
		// Lekérjük az állapotokat
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		// Megnézzük a state matcheket és létrehozzuk az entry locationöket
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {				
			// Létrehozzuk a locationt, majd a megfelelõ éleket a megfelelõ location-ökbe kötjük
			Location stateEntryLocation = createEntryLocation(compositeStateMatch.getCompositeState(), compositeStateMatch.getParentRegion());
			// Átállítjuk a bejövõ élek targetjét, ehhez felhasználjuk az összes élet lekérdezõ metódust
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, null, compositeStateMatch.getCompositeState())) {
				if ((transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition()))) {
					//throw new Exception("The transition is not mapped: " + sourceAndTargetOfTransitionsMatch.getTransition().getSource().getName() + " -> " + sourceAndTargetOfTransitionsMatch.getTransition().getTarget().getName());
					transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).setTarget(stateEntryLocation);
				}	
				
			}
		}
	}
	
	/**
	 * Ez a metódus létrehoz egy (committed) entry locationt egy state-nek.
	 * @param vertex A Yakindu vertex, amelynek entry locationt szeretnénk létrehozni.
	 * @param region Yakindu region, a state parentRegionje.
	 * @return Uppaal location, amely a megadott vertex entry locationeként funkcionál.
	 */
	private Location createEntryLocation(Vertex vertex, Region region) {
		// Létrehozzuk a locationt, majd a megfelelõ éleket a megfelelõ location-ökbe kötjük
		Location stateEntryLocation = builder.createLocation("EntryLocationOf" + vertex.getName() + (entryStateId++), regionTemplateMap.get(region));
		builder.setLocationCommitted(stateEntryLocation);
		Edge entryEdge = builder.createEdge(regionTemplateMap.get(region));
		builder.setEdgeSource(entryEdge, stateEntryLocation);
		builder.setEdgeTarget(entryEdge, stateLocationMap.get(vertex));
		// Berakjuk a state-edge párt a map-be
		hasEntryLoc.put(vertex, entryEdge);
		return stateEntryLocation;
	}
	
	/**
	 * Ez a metódus hozza létre a composite state-ekbe vezetõ élen a broadcast ! szinkornizációs csatornát, 
	 * és az eggyel alatta lévõ régiókban a ? szinkornizációs csatornákat. Ez utóbbi végpontja attól függ, hogy értelmezett-e a régióban history.
	 * @throws Exception 
	 */
	private void createEntryEdgesForAbstractionLevels() throws Exception {
		// Lekérjük a composite állapotokat
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		// Megnézzük a state matcheket és létrehozzuk az entry locationöket
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge entryEdge = hasEntryLoc.get(compositeStateMatch.getCompositeState());
			builder.setEdgeSync(entryEdge, syncChanVar + (syncChanId), true);
			for (Region subregion : compositeStateMatch.getCompositeState().getRegions()) {
				generateNewInitLocation(subregion);
			}
			// Minden eggyel alatti régióban létrehozzuk a szükséges ? sync-eket
			setAllRegionsWithSync(true, compositeStateMatch.getCompositeState().getRegions());			
		}
	}
	
	/**
	 * Ez a metódus hozza létre a composite state-ekbõl kivezetõ éleken a broadcast ! szinkronizációs csatornát,
	 * és minden alatta lévõ régióban a ? szinkronizációs csatornákat. Utóbbi esetben a csatorna mindig önmagába vezet.
	 * @throws Exception 
	 */
	private void createExitEdgesForAbstractionLevels() throws Exception {
		int id = 0;
		// Lekérjük a composite állapotokat
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		// Megnézzük az összes compositeState matchet
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			// Minden kimenõ élre ráírjuk a kilépési sync-et
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, compositeStateMatch.getCompositeState(), null)) {				
				if (transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition())) { // So we investigate only same region edges
					if (transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).getSynchronization() == null) {
						builder.setEdgeSync(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), syncChanVar + (syncChanId), true);
					}
					else {
						Edge syncEdge = createSyncLocation(stateLocationMap.get(sourceAndTargetOfTransitionsMatch.getTarget()), "CompositeSyncLocation" + (++id), null);
						builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), true);
						builder.setEdgeTarget(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), builder.getEdgeSource(syncEdge));
					}	
				}
			}
			// Letiltjuk az összes alatta lévõ region-t
			List<Region> subregionList = new ArrayList<Region>();
			Helper.addAllSubregionsToRegionList(compositeStateMatch.getCompositeState(), subregionList);
			setAllRegionsWithSync(false, subregionList);			
		}
	}
	
	/**
	 * Minden megadott régióban létrehozza a ? szinkronizációs csatornákat, és azokon az érvényességi változók updatejeit. Illetve egy initLocation-t, ha elõször építjük fel a template-et.
	 * Ezek a csatornák vagy önmagukba vagy az region entrybe vezetnek.
	 * @param needInit Kell-e initLocation a template-be.
	 * @param regionList Yakindu regionök listája, amelyeken létre szeretnénk hozni a ? csatornákat az update-ekkel.
	 * @throws Exception 
	 */
	private void setAllRegionsWithSync(boolean toBeTrue, List<Region> regionList) throws Exception {
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		for (Region subregion : regionList) {	
			for (VerticesOfRegionsMatch verticesOfRegionMatch : verticesOfRegionsMatcher.getAllMatches(subregion, null)) {
				// Choice-okból nem csinálunk magukba éleket, azokban elvileg nem tartózkodhatunk
				if (!(Helper.isChoice(verticesOfRegionMatch.getVertex())) && !(Helper.isEntry(verticesOfRegionMatch.getVertex()))) {
					Edge syncEdge = builder.createEdge(regionTemplateMap.get(subregion));
					builder.setEdgeSync(syncEdge, syncChanVar + syncChanId, false);
					builder.setEdgeUpdate(syncEdge, isActiveVar + " = " + ((toBeTrue) ? "true" : "false"));
					builder.setEdgeSource(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
					// Ha belépésre engedélyezzük a régiót, akkor vizsgálni kell, hogy hova kössük a szinkornizációs él végpontját
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
					// Kilépéskor nem vizsgálhatjuk, hogy van-e history pl.: entryLoc-ja van valamelyik composite state-nek és az committed
					else {
						builder.setEdgeTarget(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
					}
				}
			}			
		}
	}
	
	/**
	 * This method creates a init location int the mapped region (in the template equivalent) and ties it to the entry of the region.
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
	 * Létrehozza azokat az éleket, amelyeknek végpontjai nem ugyanazon regionben találhatók.
	 * @throws Exception 
	 */
	private void createEdgesForDifferentAbstraction() throws Exception {
		//Lekérjük a transition match-eket		
		EdgesAcrossRegionsMatcher edgesAcrossRegionsMatcher = engine.getMatcher(EdgesAcrossRegionsQuerySpecification.instance());
		// Megnézzük a transition match-eket és létrehozzuk az edge-eket a megfelelõ guardokkal és effectekkel
		for (EdgesAcrossRegionsMatch acrossTransitionMatch : edgesAcrossRegionsMatcher.getAllMatches()) {											
			// Ha a két végpont nem azonos region-ben van:
			// Megnézzük melyik milyen szintû, és aszerint hozzuk létre a szinkronizációs csatornákat
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
	 * Létrehozza az absztrakciós szintek közötti tranziciókhoz szükséges éleket.
	 * Csak akkor mûködik, ha a source szintje kisebb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzició kezdõpontja.
	 * @param target Yakindu vertex, a tranzició végpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfelsõ szinten létrehozott edget.
	 * @param lastLevel Egész szám, amely megmondja, hogy a target hányadik szinten van.
	 * @throws Exception 
	 */ 
	private void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions) throws Exception {
		// Rekurzió:
		// Visszamegyünk a legfölsõ szintre, majd onnan visszalépkedve, sorban minden szinten létrehozzuk a szinkronizációkat
		if (source.getParentRegion() != target.getParentRegion()) {
			visitedRegions.add(target.getParentRegion());
			createEdgesWhenSourceLesser(source, (Vertex) target.getParentRegion().getComposite(), transition, lastLevel, levelDifference, visitedRegions);
		}
		// Ha a legfölsõ szintet elértük:
		// Létrehozunk új sync változót, és a source-ból a composite statebe vezetünk egy élet a sync változóval
		if (source.getParentRegion() == target.getParentRegion()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge abstractionEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(abstractionEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(abstractionEdge, stateLocationMap.get(target));
			builder.setEdgeSync(abstractionEdge, syncChanVar + (syncChanId), true);
			builder.setEdgeComment(abstractionEdge, "A Yakinduban alacsonyabb absztrakcios szinten levo vertexbe vezeto el.");
			// Ha a targetnek van entryEventje, akkor azt rá kell írni az élre
			setHelperEdgeEntryEvent(abstractionEdge, target, lastLevel);
			// Ez az él felel majd meg a regionökön átívelõ transitionnek
			transitionEdgeMap.put(transition, abstractionEdge);
			// A target composite state, belépésre minden alrégiójába is belépünk
			setEdgeEntryAllSubregions(target, visitedRegions);
		}	
		// Ha nem a legfölsõ szinten vagyunk, akkor létrehozzuk a ? szinkronizációs éleket minden állapotból a megfelelõ állapotba
		else {
			VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
			for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches(target.getParentRegion(), null)) {				
				setHelperEdges(stateLocationMap.get(verticesOfRegionsMatch.getVertex()), target, lastLevel);
			}
			// Altemplate "initial location"-jét is bekötjük a megfelelõ locationbe
			if (hasInitLoc.containsKey(regionTemplateMap.get(target.getParentRegion()))) {
				setHelperEdges(hasInitLoc.get(regionTemplateMap.get(target.getParentRegion())), target, lastLevel);				
			}
			// Ha a target composite state, akkor ezt minden region-jére megismételjük, kivéve ezt a regiont
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
	
	private void setHelperEdges(Location source, Vertex target, int lastLevel) throws IncQueryException {
		Edge syncEdge = builder.createEdge(source.getParentTemplate());					
		builder.setEdgeSource(syncEdge, source);		
		// Ha utolsó szinten vagyunk, és egy composite state-be megyünk, akkor az entryLocjába kell kötni
		if (lastLevel == Helper.getLevelOfVertex(target) && hasEntryLoc.containsKey(target)) {
			builder.setEdgeTarget(syncEdge, builder.getEdgeSource(hasEntryLoc.get(target)));
		}							
		// Itt már nem kell entryLocba kötni, mert az lehet, hogy elrontaná az alsóbb régiók helyes állapotatit (tehát csak legalsó szinten kell entryLocba kötni)
		else {					
			builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));
		}					
		// Ha a targetnek van entryEventje, akkor azt rá kell írni az élre
		setHelperEdgeEntryEvent(syncEdge, target, lastLevel);
		builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), false);
		builder.setEdgeUpdate(syncEdge, isActiveVar + " = true");
	}
	
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
	
	private void setEdgeEntryAllSubregions(Vertex target, List<Region> visitedRegions) throws Exception {		
		List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions()); // Talán addAll kéne?
		pickedSubregions.removeAll(visitedRegions);
		setAllRegionsWithSync(true, pickedSubregions);		
		setSyncFromGeneratedInit(pickedSubregions);
	}
	
	/**
	 * Létrehozza az absztrakciós szintek közötti tranziciókhoz szükséges éleket.
	 * Csak akkor mûködik, ha a source szintje nagyobb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzició kezdõpontja.
	 * @param target Yakindu vertex, a tranzició végpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfelsõ szinten létrehozott edget.
	 * @param lastLevel Egész szám, amely megmondja, hogy a source hányadik szinten van.
	 * @throws Exception 
	 */
	private void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel, List<Region> visitedRegions) throws Exception {
		// A legalsó szinten létrehozunk egy magába vezetõ élet:  
		// Ez felel meg az alacsonyabb szintrõl magasabb szinten lévõ vertexbe vezetõ átmenetnek
		if (Helper.getLevelOfVertex(source) == lastLevel) {
			visitedRegions.add(source.getParentRegion());
			// Létrehozunk egy szinkronizációs csatornát rá
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), true);
			// Letiltjuk ezt a régiót, mert önmagára nem tud szinkronizálni
			builder.setEdgeUpdate(ownSyncEdge, isActiveVar + " = false");
			builder.setEdgeComment(ownSyncEdge, "A Yakinduban magasabb absztrakcios szinten levo vertexbe vezeto el.");
			// Ez az él felel majd meg a regionökön átívelõ transitionnek
			transitionEdgeMap.put(transition, ownSyncEdge);
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel, visitedRegions);
		}
		// A felsõ szint
		else if (Helper.getLevelOfVertex(source) == Helper.getLevelOfVertex(target)) {
			// A felsõ szinten létrehozzuk az élet, amely fogadja a szinkronizációt
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(target));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), false);
			// Exit eventet rárakjuk, ha van
			setHelperEdgeExitEvent(ownSyncEdge, source, lastLevel);
			// Itt letiltjuk az összes source alatt lévõ régiót, jelezve, hogy azok már nem érvényesek
			// Kivéve a meglátogatottakat
			setEdgeExitAllSubregions(source, visitedRegions);
			return;
		}
		// Közbülsõ szinteken csak kézzel létrehozzuk a sync éleket, letiltjuk a régiót, és rájuk írjuk az exit expressiont, ha van
		else {
			visitedRegions.add(source.getParentRegion());
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), false);
			builder.setEdgeUpdate(ownSyncEdge, isActiveVar + " = false");
			setHelperEdgeExitEvent(ownSyncEdge, source, lastLevel);
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel, visitedRegions);
		}
	}
	
	private void setHelperEdgeExitEvent(Edge edge, Vertex source, int lastLevel) throws IncQueryException {
		if (Helper.hasExitEvent(source)) {
			for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : runOnceEngine.getAllMatches(StatesWithExitEventWithoutOutgoingTransitionMatcher.querySpecification())) {
				if (statesWithExitEventMatch.getState() == source) {
					String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());
					builder.setEdgeUpdate(edge, effect);						
				}
			}
		}
	}
	
	private void setEdgeExitAllSubregions(Vertex source, List<Region> regionsToRemove) throws Exception {
		List<Region> subregionList = new ArrayList<Region>();
		State sourceState = (State) source ;
		Helper.addAllSubregionsToRegionList(sourceState, subregionList);
		subregionList.removeAll(regionsToRemove);
		setAllRegionsWithSync(false, subregionList);
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eken az update-eket a Yakindu effectek alapján.
	 * @throws Exception 
	 */
	private void setEdgeUpdates() throws Exception {
		// Végigmegyünk minden transition-ön, amelynek van effectje
		EdgesWithEffectMatcher edgesWithEffectMatcher = engine.getMatcher(EdgesWithEffectQuerySpecification.instance());
		for (EdgesWithEffectMatch edgesWithEffectMatch : edgesWithEffectMatcher.getAllMatches()) {
			String effect = UppaalCodeGenerator.transformExpression(edgesWithEffectMatch.getExpression());
			builder.setEdgeUpdate(transitionEdgeMap.get(edgesWithEffectMatch.getTransition()), effect);
		}
		// Megcsiáljuk a state update-eket is
		setEdgeUpdatesFromStates();
		// Itt csináljuk meg a raise eventeket
		createRaisingEventSyncs();
	}
	
	/**
	 * Ez a metódus felel az egyes state-ek entry/exit triggerjeinek hatásaiért.
	 * Minden Entry triggerrel rendelkezõ state esetén létrehoz egy committed location-t,
	 * amelybõl a kivezetõ él a megfelelõ locationba vezet, és tartalmazza a szükséges update-eket.
	 * Minden Exit triggerrel rendelkezõ state esetén a kimenõ élekre rakja rá a szükséges update-eket.
	 * Könnyen belátható, hogy Exit esetén nem mûködne az Entry-s megoldás.
	 * @throws IncQueryException 
	 */
	private void setEdgeUpdatesFromStates() throws IncQueryException {
		EdgesInSameRegionMatcher edgesInSameRegionMatcher = engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
			// Transzformáljuk a kifejezést
			String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
			// Ha nincs még entry state-je
			if (!hasEntryLoc.containsKey(statesWithEntryEventMatch.getState())) {
				// Létrehozzuk a locationt, majd a megfelelõ éleket a megfelelõ location-ökbe kötjük
				Location stateEntryLocation = createEntryLocation(statesWithEntryEventMatch.getState(), statesWithEntryEventMatch.getParentRegion());
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
				// Átállítjuk a bejövõ élek targetjét
				for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, statesWithEntryEventMatch.getState(), null)) {				
					builder.setEdgeTarget(transitionEdgeMap.get(edgesInSameRegionMatch.getTransition()), stateEntryLocation);				
				}
			}
			// Ha már van entry state-je
			else {
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
			}
		}
		// Ha Exit, akkor ráírjuk az update-et minden kimenõ élre
		// Nem használhatunk committed locationt
		for (StatesWithExitEventMatch statesWithExitEventMatch : runOnceEngine.getAllMatches(StatesWithExitEventMatcher.querySpecification())) {
			String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());			
			builder.setEdgeUpdate(transitionEdgeMap.get(statesWithExitEventMatch.getTransition()), effect);			
		}
	}
	
	/**
	 * Creates local reactions as a loop edge or two edges with a sync location.
	 * @throws Exception
	 */
	private void createLocalReactions() throws Exception {
		String guard = null;
		for (LocalReactionOnlyGuardMatch localReactionValueOfGuardMatch : runOnceEngine.getAllMatches(LocalReactionOnlyGuardMatcher.querySpecification())) {
			Location stateLocation = stateLocationMap.get(localReactionValueOfGuardMatch.getState());
			Edge localReactionEdge = builder.createEdge(stateLocation.getParentTemplate());
			builder.setEdgeSource(localReactionEdge, stateLocation);
			builder.setEdgeTarget(localReactionEdge, stateLocation);			
			if (Helper.isEventName(localReactionValueOfGuardMatch.getName())) {
				guard = Helper.getInEventValueName(localReactionValueOfGuardMatch.getName()) + " " + localReactionValueOfGuardMatch.getOperator().getLiteral() + " " + UppaalCodeGenerator.transformExpression(localReactionValueOfGuardMatch.getGuardRightOperand());
				builder.setEdgeSync(localReactionEdge, localReactionValueOfGuardMatch.getName(), false);
			} 
			else {
				guard = localReactionValueOfGuardMatch.getName() + " " + localReactionValueOfGuardMatch.getOperator().getLiteral() + " " + UppaalCodeGenerator.transformExpression(localReactionValueOfGuardMatch.getGuardRightOperand());				
			}
			builder.setEdgeGuard(localReactionEdge, guard);
			createRaisingLocationForLocalReaction(localReactionValueOfGuardMatch.getLocalReaction(), localReactionEdge);
		}
		for (LocalReactionPlainMatch localReactionPlainMatch : runOnceEngine.getAllMatches(LocalReactionPlainMatcher.querySpecification())) {
			Location stateLocation = stateLocationMap.get(localReactionPlainMatch.getState());
			Edge localReactionEdge = builder.createEdge(stateLocation.getParentTemplate());
			builder.setEdgeSource(localReactionEdge, stateLocation);
			builder.setEdgeTarget(localReactionEdge, stateLocation);
			builder.setEdgeSync(localReactionEdge, UppaalCodeGenerator.transformExpression(localReactionPlainMatch.getExpression()), false);
			if (localReactionPlainMatch.getReactionTrigger().getGuard() != null) {
				guard = UppaalCodeGenerator.transformExpression(localReactionPlainMatch.getReactionTrigger().getGuard().getExpression());
				builder.setEdgeGuard(localReactionEdge, guard);
			}
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
	 * Ez a metódus létrehozza az UPPAAL edge-eken az guardokat a Yakindu guardok alapján.
	 * @throws IncQueryException 
	 */
	private void setEdgeGuards() throws IncQueryException {
		// Végigmegyünk minden transition-ön
		EdgesWithGuardMatcher edgesWithGuardMatcher = engine.getMatcher(EdgesWithGuardQuerySpecification.instance());
		EventsWithTypeMatcher eventsWithTypeMatcher = engine.getMatcher(EventsWithTypeQuerySpecification.instance());
		for (EdgesWithGuardMatch edgesWithGuardMatch : edgesWithGuardMatcher.getAllMatches()) {
			// Ha van guard-ja, akkor azt transzformáljuk, és ráírjuk az edge-re
			String guard = " " + UppaalCodeGenerator.transformExpression(edgesWithGuardMatch.getExpression());
			for (EventsWithTypeMatch eventsWithTypeMatch : eventsWithTypeMatcher.getAllMatches()) {
				// If the guard expression cointains an in event variable, then it has to be replaced by the in event variable.
				if (guard.contains(" " + eventsWithTypeMatch.getName() + " ")) {
					guard = guard.replaceAll(" " + eventsWithTypeMatch.getName() + " ", " " + Helper.getInEventValueName(eventsWithTypeMatch.getName()) + " ");	
					builder.setEdgeSync(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), eventsWithTypeMatch.getName(), false);
				}
			}
			if (builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != null && builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != "") {
				builder.setEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) + " && " + guard);
			}
			else {					
				builder.setEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), guard);
			}		
		}
	}
	
	/**
	 * Létrehozza a template-ek érvényes mûködéséhez szükséges guardokat. (isActive)
	 * @param transition A Yakindu transition, amelynek a megfeleltetett UPPAAL élére rakjuk rá az érvényességi guardot.
	 * @throws IncQueryException 
	 */
	private void createTemplateValidityGuards() throws IncQueryException {
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches()) {
			// Rátesszük a guardokra a template érvényességi vátozót is
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
	 * @throws Exception jelzi, ha nem mûködik a szinkronizáció. 
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
	 * Ez a metódus hozza létre az egyes Yakundu kimeneti éleken szereplõ idõfüggõ viselkedésnek megfelelõ (after 1 s) Uppaal clock változók manipulálását.
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
	 * This method is responsible for creating the control template, the synchronization values and gather all the integer values that can be added as an in value. 
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
