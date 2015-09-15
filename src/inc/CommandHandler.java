package inc;

import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelBuilder;
import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelSaver;
import hu.bme.mit.inf.alf.uppaal.transformation.serialization.UppaalModelSerializer;

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
import org.eclipse.incquery.runtime.exception.IncQueryException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.State;
import org.yakindu.sct.model.sgraph.Statechart;
import org.yakindu.sct.model.sgraph.Transition;
import org.yakindu.sct.model.sgraph.Vertex;

import de.uni_paderborn.uppaal.templates.Edge;
import de.uni_paderborn.uppaal.templates.Location;
import de.uni_paderborn.uppaal.templates.Template;

/**
 * Az osztály, amely a Yakindu példánymodell alapján létrehozza az UPPAAL példánymodellt.
 * Függ a PatternMatcher és az UppaalModelBuilder osztályoktól.
 * @author Graics Bence 
 * Kell még:
 * -Synchronization node?
 * -Idõmérés?
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
	 
	// Uppaal változónevek
	private final String syncChanVar = "syncChan";
	private final String isActiveVar = "isActive";
	private final String clockVar = "Timer";
			
	// Az IncQuery illeszkedések lekérésére
	private PatternMatcher matcher = null;
			
	// Az UPPAAL modell felépítésre
	private UppaalModelBuilder builder = null;	
			
	// Egy Map a Yakindu:Region -> UPPAAL:Template leképzésre									 								
	private Map<Region, Template> regionTemplateMap = null;
			
	// Egy Map a Yakindu:Vertex -> UPPAAL:Location leképzésre									 								
	private Map<Vertex, Location> stateLocationMap = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge leképzésre
	private Map<Transition, Edge> transitionEdgeMap = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge leképzésre
	private Map<Vertex, Edge> hasEntryLoc = null;
	
	// Egy Map, amely tárolja az egyes Vertexek triggerLocation kimenõ élét
	private Map<Transition, Edge> hasTriggerPlusEdge = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge leképzésre
	private Map<Vertex, Edge> hasExitLoc = null;
	
	// Egy Map, amely tárolja az altemplate-ek "initial location"-jét
	private Map<Template, Location> hasInitLoc = null;
			
	// Szinkronizációs csatornák létrehozására
	private int syncChanId = 0;
	// EntryLoc név generálásra
	private int entryStateId = 0;
	// ExitLoc név generálásra
	private int exitStateId = 0;	

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

								matcher = new PatternMatcher();
								Helper.setMatcher(matcher);
								matcher.setResource(resource); // IncQuery engine inicializáció a resource-ra: statechartot tartalmazó fájl
								
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
									hasExitLoc = new HashMap<Vertex, Edge>();
									hasInitLoc = new HashMap<Template, Location>();
									
									// ID változók resetelése
									syncChanId = 0;
									entryStateId = 0;
									exitStateId = 0;
									
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
		Collection<VariableDefinitionsMatch> allVariableDefinitions = matcher.getAllVariables();
		System.out.println("A változók száma: " + allVariableDefinitions.size());
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
		Collection<EntryOfRegionsMatch> regionMatches = matcher.getAllRegionsWithEntry();
		// Végigmegyünk a régiókon, és létrehozzuk a Yakindu modellnek megfeleltethetõ elemeket.
		for (EntryOfRegionsMatch regionMatch : regionMatches) {
			Template template = null;			
			// Kiszedjük a template nevekbõl a szóközöket, mert az UPPAAL nem szereti
			if (Helper.isTopRegion(regionMatch.getRegion())) {
				template = builder.createTemplate(regionMatch.getRegionName().replaceAll(" ", "") + "OfStatechart");
				// Mégis foglalkozunk, hogy a regionökön átívelõ tranziciók helyes lefutása garantálható legyen
				builder.addLocalDeclaration("bool " + isActiveVar + " = true;", template);
			} 
			else {
				template = builder.createTemplate(regionMatch.getRegionName().replaceAll(" ", "") + "Of" + ((State) regionMatch.getRegion().getComposite()).getName());
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
		
		// Template guardok berakása
		createTemplateValidityGuards();
		
		// Entry kimenõ élek beSyncelése
		createSyncFromEntries();
		
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
		Collection<StatesMatch> allStateMatches = matcher.getAllStates();
		// Megnézzük a state matcheket és létrehozzuk a location-öket
		for (StatesMatch stateMatch : allStateMatches) {				
			if (stateMatch.getParentRegion() == region) {										
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
		Collection<ChoicesMatch> allChoices = matcher.getAllChoices();
		// Megnézzük a state matcheket és létrehozzuk a location-öket		
		for (ChoicesMatch choiceMatch : allChoices) {
			if (choiceMatch.getRegion() == region) {										
				Location aLocation = builder.createLocation("Choice" + id++, template);
				builder.setLocationCommitted(aLocation);
				stateLocationMap.put(choiceMatch.getChoice(), aLocation); // A choice-location párokat betesszük a map-be	
				builder.setLocationComment(aLocation, "A choice");
			}
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
		Collection<FinalStatesMatch> allFinalStates = matcher.getAllFinalStates();
		// Megnézzük a state matcheket és létrehozzuk a location-öket		
		for (FinalStatesMatch finalStateMatch : allFinalStates) {					
			if (finalStateMatch.getRegion() == region) {										
				Location aLocation = builder.createLocation("FinalState" + id++, template);
				stateLocationMap.put(finalStateMatch.getFinalState(), aLocation); // A final state-location párokat betesszük a map-be	
				builder.setLocationComment(aLocation, "A final state");
			}
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL locationöket a Yakindu exit node-ok alapján az ExitNodesMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a final state-ek valók.
	 * @param template Az UPPAAL template, amelybe a locationöket kell rakni
	 * @throws IncQueryException
	 */
	private void createLocationsFromExitNodes(Region region, Template template) throws IncQueryException {
		// A különbözõ final state-ek megkülönböztetésére
		int id = 0; 
		// Lekérjük a final state-eket
		Collection<ExitNodesMatch> allExitNodes = matcher.getAllExitNodes();
		// Megnézzük a state matcheket és létrehozzuk a location-öket		
		for (ExitNodesMatch exitNodesMatch : allExitNodes) {						
			if (exitNodesMatch.getRegion() == region) {
				// Létrehozunk egy új locationt
				Location exitNode = builder.createLocation("ExitNode" + (id++), template);
				stateLocationMap.put(exitNodesMatch.getExit(), exitNode); // Az exit node-location párokat betesszük a map-be	
				builder.setLocationComment(exitNode, "An exit node");
			}
		}
	}
	
	/**
	 * Ez a metódus felelõs az exit node-okba vezetõ élek broadcast ! szinkronizációjának, és a felette lévõ régiók ? szinkornizációjának létrehozásáért.
	 * @throws IncQueryException
	 */
	private void createUpdatesForExitNodes() throws IncQueryException {
		// Lekérjük az exit node-okat
		Collection<ExitNodesMatch> allExitNodes = matcher.getAllExitNodes();
		for (ExitNodesMatch exitNodesMatch : allExitNodes) {
			// Beállítjuk, hogy az összes átmenet elveszi az összes felette lévõ region validságát
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");	
			for (SourceAndTargetOfTransitionsMatch transitionMatch : matcher.getAllTransitions()) {
				if (transitionMatch.getTarget() == exitNodesMatch.getExit()) {
					// Ez csak nem composite state-ekre megy, hiszen egy élen csak egy szinkronizáció lehet (és composite esetén a kimenõnek van már)
					if (!Helper.isCompositeState(transitionMatch.getSource())) {	
						builder.setEdgeSync(transitionEdgeMap.get(transitionMatch.getTransition()), syncChanVar + (syncChanId), true);
						builder.setEdgeUpdate(transitionEdgeMap.get(transitionMatch.getTransition()), isActiveVar + " = false");
						// Guardot nem állítunk, azt majd a közös metódusban
						builder.setEdgeComment(transitionEdgeMap.get(transitionMatch.getTransition()), "Exit node-ba vezeto el, kilep a templatebol.");
					}
					// Ha composite state, létrehozunk egy exit locationt
					else {
						Location exitLoc = builder.createLocation("CompositeStateExit" + (exitStateId++), regionTemplateMap.get(transitionMatch.getSource().getParentRegion()));
						builder.setLocationCommitted(exitLoc);
						Edge exitEdge = builder.createEdge(regionTemplateMap.get(transitionMatch.getSource().getParentRegion()));
						builder.setEdgeSource(exitEdge, exitLoc);
						builder.setEdgeTarget(exitEdge, stateLocationMap.get(exitNodesMatch.getExit()));
						builder.setEdgeSync(exitEdge, syncChanVar + (syncChanId), true);
						builder.setEdgeUpdate(exitEdge, isActiveVar + " = false");
						// Guardot nem állítunk, azt majd a közös metódusban
						builder.setEdgeComment(exitEdge, "Exit node-ba vezeto el, kilep a templatebol.");
						// Bejövõ él targetjét átállítjuk az exitLoc-ra
						builder.setEdgeTarget(transitionEdgeMap.get(transitionMatch.getTransition()), exitLoc);
						// Composite state-et betesszük a mapbe
						hasExitLoc.put(transitionMatch.getSource(), exitEdge);
					}
				}
			}
			// Letiltjuk az összes felette lévõ régiót, ehhez lekérjük a felette lévõ regionöket
			List<Region> regionList = new ArrayList<Region>();
			regionList = Helper.getThisAndUpperRegions(regionList, exitNodesMatch.getRegion());
			// Kivesszük a saját regionét
			regionList.remove(exitNodesMatch.getRegion());
			// Letiltjuk a régiókat
			setAllRegionsWithSync(false, false, regionList);
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eket az azonos regionbeli Yakindu transition-ök alapján az EdgesInSameRegionMatcheket felhasználva.
	 * @param region A Yakindu region, amelybõl a transitionök valók.
	 * @param template Az UPPAAL template, amelybe az edgeket kell rakni.
	 * @throws IncQueryException
	 */
	private void createEdges(Region region, Template template) throws IncQueryException {
		//Lekérjük a transition match-eket		
		Collection<EdgesInSameRegionMatch> edgesInSameRegionMatches = matcher.getAllEdgesInSameRegion();	
		// Megnézzük a transition match-eket és létrehozzuk az edge-eket a megfelelõ guardokkal és effectekkel
		for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatches) {											
			// Ha a két végpont a helyes region-ben van
			if (edgesInSameRegionMatch.getParentRegion() == region &&
					stateLocationMap.containsKey(edgesInSameRegionMatch.getSource()) && stateLocationMap.containsKey(edgesInSameRegionMatch.getTarget())) {								
				//Létrehozunk egy edge-t
				Edge anEdge = builder.createEdge(template); 
				//Beállítjuk az edge forrását és célját
				anEdge.setSource(stateLocationMap.get(edgesInSameRegionMatch.getSource()));
				anEdge.setTarget(stateLocationMap.get(edgesInSameRegionMatch.getTarget()));
				transitionEdgeMap.put(edgesInSameRegionMatch.getTransition(), anEdge);
			}				
		}
	}
	
	/**
	 * Ez a metódus hozza létre a composite state-ek entry locationjét. Ez a state-be lépéskor az alrégiókba való lépés miatt szükséges.
	 * (Hogy az minden esetben megvalósuljon.)
	 * @throws IncQueryException
	 */
	private void createEntryForCompositeStates() throws IncQueryException {
		// Lekérjük az állapotokat
		Collection<CompositeStatesMatch> allCompositeStatesMatches = matcher.getAllCompositeStates();
		// Megnézzük a state matcheket és létrehozzuk az entry locationöket
		for (CompositeStatesMatch compositeStateMatch : allCompositeStatesMatches) {				
			// Létrehozzuk a locationt, majd a megfelelõ éleket a megfelelõ location-ökbe kötjük
			Location stateEntryLocation = createEntryLocation(compositeStateMatch.getCompositeState(), compositeStateMatch.getParentRegion());
			// Átállítjuk a bejövõ élek targetjét, ehhez felhasználjuk az összes élet lekérdezõ metódust
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
				if (sourceAndTargetOfTransitionsMatch.getTarget() == compositeStateMatch.getCompositeState()) {
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
	 * @throws IncQueryException
	 */
	private void createEntryEdgesForAbstractionLevels() throws IncQueryException {
		// Lekérjük a composite állapotokat
		Collection<CompositeStatesMatch> allCompositeStatesMatches = matcher.getAllCompositeStates();
		// Megnézzük a state matcheket és létrehozzuk az entry locationöket
		for (CompositeStatesMatch compositeStateMatch : allCompositeStatesMatches) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge entryEdge = hasEntryLoc.get(compositeStateMatch.getCompositeState());
			builder.setEdgeSync(entryEdge, syncChanVar + (syncChanId), true);
			// Minden eggyel alatti régióban létrehozzuk a szükséges ? sync-eket
			setAllRegionsWithSync(true, true, compositeStateMatch.getCompositeState().getRegions());
		}
	}
	
	/**
	 * Ez a metódus hozza létre a composite state-ekbõl kivezetõ éleken a broadcast ! szinkronizációs csatornát,
	 * és minden alatta lévõ régióban a ? szinkronizációs csatornákat. Utóbbi esetben a csatorna mindig önmagába vezet.
	 * @throws IncQueryException
	 */
	private void createExitEdgesForAbstractionLevels() throws IncQueryException {
		// Lekérjük a composite állapotokat
		Collection<CompositeStatesMatch> allCompositeStateMatches = matcher.getAllCompositeStates();
		// Megnézzük az összes compositeState matchet
		for (CompositeStatesMatch compositeStateMatch : allCompositeStateMatches) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			// Minden kimenõ élre ráírjuk a kilépési sync-et
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
				if (sourceAndTargetOfTransitionsMatch.getSource() == compositeStateMatch.getCompositeState()) {
					builder.setEdgeSync(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), syncChanVar + (syncChanId), true);
				}
			}
			// Letiltjuk az összes alatta lévõ region-t
			List<Region> subregionList = new ArrayList<Region>();
			Helper.addAllSubregionsToRegionList(compositeStateMatch.getCompositeState(), subregionList);
			setAllRegionsWithSync(false, false, subregionList);			
		}
	}
	
	/**
	 * Minden megadott régióban létrehozza a ? szinkronizációs csatornákat, és azokon az érvényességi változók updatejeit.
	 * Ezek a csatornák vagy önmagukba vagy az region entrybe vezetnek.
	 * @param toBeTrue Engedélyezni vagy tiltani szeretnénk-e a régiókat.
	 * @param regionList Yakindu regionök listája, amelyeken létre szeretnénk hozni a ? csatornákat az update-ekkel.
	 * @throws IncQueryException 
	 */
	private void setAllRegionsWithSync(boolean toBeTrue, boolean needInit, List<Region> regionList) throws IncQueryException {
		for (Region subregion : regionList) {
			if (needInit) {
				Location initLocation = builder.createLocation("Init", regionTemplateMap.get(subregion));
				Edge syncEdge = builder.createEdge(regionTemplateMap.get(subregion));
				builder.setEdgeSync(syncEdge, syncChanVar + syncChanId, false);
				builder.setEdgeUpdate(syncEdge, isActiveVar + " = " + ((toBeTrue) ? "true" : "false"));
				builder.setEdgeSource(syncEdge, initLocation);
				builder.setEdgeTarget(syncEdge, stateLocationMap.get(Helper.getEntryOfRegion(subregion)));
				builder.setInitialLocation(initLocation, regionTemplateMap.get(subregion));
				hasInitLoc.put(regionTemplateMap.get(subregion), initLocation);
			}			
			for (VerticesOfRegionsMatch verticesOfRegionMatch : matcher.getAllVerticesOfRegions()) {
				// Az adott subregion vertexeit vizsgáljuk
				if (verticesOfRegionMatch.getRegion() == subregion) {
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
	}
	
	/**
	 * Létrehozza azokat az éleket, amelyeknek végpontjai nem ugyanazon regionben találhatók.
	 * @throws IncQueryException
	 */
	private void createEdgesForDifferentAbstraction() throws IncQueryException {
		//Lekérjük a transition match-eket		
		Collection<EdgesAcrossRegionsMatch> allAcrossTransitionMatches = matcher.getAllEdgesAcrossRegions();	
		// Megnézzük a transition match-eket és létrehozzuk az edge-eket a megfelelõ guardokkal és effectekkel
		for (EdgesAcrossRegionsMatch acrossTransitionMatch : allAcrossTransitionMatches) {											
			// Ha a két végpont nem azonos region-ben van:
			// Megnézzük melyik milyen szintû, és aszerint hozzuk létre a szinkronizációs csatornákat
			if (stateLocationMap.containsKey(acrossTransitionMatch.getSource()) && stateLocationMap.containsKey(acrossTransitionMatch.getTarget())) {								
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
	}
	
	/**
	 * Létrehozza az absztrakciós szintek közötti tranziciókhoz szükséges éleket.
	 * Csak akkor mûködik, ha a source szintje kisebb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzició kezdõpontja.
	 * @param target Yakindu vertex, a tranzició végpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfelsõ szinten létrehozott edget.
	 * @param lastLevel Egész szám, amely megmondja, hogy a target hányadik szinten van.
	 * @throws IncQueryException 
	 */ 
	private void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions) throws IncQueryException {
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
			if (Helper.hasEntryEvent(target)) {
				for (StatesWithEntryEventMatch statesWithEntryEventMatch : matcher.getAllStatesWithEntryEvent()) {
					if (statesWithEntryEventMatch.getState() == target) {
						String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
						builder.setEdgeUpdate(abstractionEdge, effect);
					}
				}
			}
			// Ez az él felel majd meg a regionökön átívelõ transitionnek
			transitionEdgeMap.put(transition, abstractionEdge);
			// Ha a target composite state, akkor belépésre minden alrégiójába is belépünk
			if (Helper.isCompositeState(target)) {
				List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions()); // Talán addAll kéne?
				pickedSubregions.removeAll(visitedRegions);
				setAllRegionsWithSync(true, false, pickedSubregions);				
			}
		}
		// Ha nem a legfölsõ szinten vagyunk, akkor létrehozzuk a ? szinkronizációs éleket minden állapotból a megfelelõ állapotba
		else {
			for (VerticesOfRegionsMatch verticesOfRegionsMatch : matcher.getAllVerticesOfRegions()) {
				if (verticesOfRegionsMatch.getRegion() == target.getParentRegion()) {
					Edge syncEdge = builder.createEdge(regionTemplateMap.get(verticesOfRegionsMatch.getRegion()));
					builder.setEdgeSource(syncEdge, stateLocationMap.get(verticesOfRegionsMatch.getVertex()));
					builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));	
					// Ha a targetnek van entryEventje, akkor azt rá kell írni az élre
					if (Helper.hasEntryEvent(target)) {
						for (StatesWithEntryEventMatch statesWithEntryEventMatch : matcher.getAllStatesWithEntryEvent()) {
							if (statesWithEntryEventMatch.getState() == target) {
								String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
								builder.setEdgeUpdate(syncEdge, effect);
							}
						}
					}
					builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), false);
					builder.setEdgeUpdate(syncEdge, isActiveVar + " = true");		
				}
			}
			// Altemplate "initial location"-jét is bekötjük a megfelelõ locationbe
			if (hasInitLoc.containsKey(regionTemplateMap.get(target.getParentRegion()))) {
				Edge syncEdge = builder.createEdge(regionTemplateMap.get(target.getParentRegion()));
				builder.setEdgeSource(syncEdge, hasInitLoc.get(regionTemplateMap.get(target.getParentRegion())));
				builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));	
				builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), false);
				builder.setEdgeUpdate(syncEdge, isActiveVar + " = true");	
			}
			// Ha a target composite state, akkor ezt minden region-jére megismételjük, kivéve ezt a regiont
			if (Helper.isCompositeState(target)) {
				List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions()); // Talán addAll kéne?
				pickedSubregions.removeAll(visitedRegions);
				setAllRegionsWithSync(true, false, pickedSubregions);				
			}
		}		
	}
	
	/**
	 * Létrehozza az absztrakciós szintek közötti tranziciókhoz szükséges éleket.
	 * Csak akkor mûködik, ha a source szintje nagyobb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzició kezdõpontja.
	 * @param target Yakindu vertex, a tranzició végpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfelsõ szinten létrehozott edget.
	 * @param lastLevel Egész szám, amely megmondja, hogy a source hányadik szinten van.
	 * @throws IncQueryException 
	 */
	private void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel, List<Region> visitedRegions) throws IncQueryException {
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
			if (Helper.hasExitEvent(source)) {
				for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : matcher.getAllStatesWithExitEventWithoutOutgoing()) {
					if (statesWithExitEventMatch.getState() == source) {
						String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());
						builder.setEdgeUpdate(ownSyncEdge, effect);						
					}
				}
			}
			// Itt letiltjuk az összes source alatt lévõ régiót, jelezve, hogy azok már nem érvényesek
			// Kivéve a meglátogatottakat
			List<Region> subregionList = new ArrayList<Region>();
			State sourceState = (State) source;
			Helper.addAllSubregionsToRegionList(sourceState, subregionList);
			subregionList.removeAll(visitedRegions);
			setAllRegionsWithSync(false, false, subregionList);
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
			if (Helper.hasExitEvent(source)) {
				for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : matcher.getAllStatesWithExitEventWithoutOutgoing()) {
					if (statesWithExitEventMatch.getState() == source) {
						String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());
						builder.setEdgeUpdate(ownSyncEdge, effect);						
					}
				}
			}
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel, visitedRegions);
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eken az update-eket a Yakindu effectek alapján.
	 * @throws Exception 
	 */
	private void setEdgeUpdates() throws Exception {
		// Végigmegyünk minden transition-ön, amelynek van effectje
		for (EdgesWithEffectMatch edgesWithEffectMatch : matcher.getAllEdgesWithEffect()) {
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
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : matcher.getAllStatesWithEntryEvent()) {
			// Transzformáljuk a kifejezést
			String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
			// Ha nincs még entry state-je
			if (!hasEntryLoc.containsKey(statesWithEntryEventMatch.getState())) {
				// Létrehozzuk a locationt, majd a megfelelõ éleket a megfelelõ location-ökbe kötjük
				Location stateEntryLocation = createEntryLocation(statesWithEntryEventMatch.getState(), statesWithEntryEventMatch.getParentRegion());
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
				// Átállítjuk a bejövõ élek targetjét
				for (EdgesInSameRegionMatch edgesInSameRegionMatch : matcher.getAllEdgesInSameRegion()) {
					if (edgesInSameRegionMatch.getTarget() == statesWithEntryEventMatch.getState()) {
						transitionEdgeMap.get(edgesInSameRegionMatch.getTransition()).setTarget(stateEntryLocation);
					}
				}
			}
			// Ha már van entry state-je
			else {
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
			}
		}
		// Ha Exit, akkor ráírjuk az update-et minden kimenõ élre
		// Nem használhatunk committed locationt
		for (StatesWithExitEventMatch statesWithExitEventMatch : matcher.getAllStatesWithExitEvent()) {
			String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());			
			builder.setEdgeUpdate(transitionEdgeMap.get(statesWithExitEventMatch.getTransition()), effect);			
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eken az guardokat a Yakindu guardok alapján.
	 * @throws IncQueryException 
	 */
	private void setEdgeGuards() throws IncQueryException {
		// Végigmegyünk minden transition-ön
		for (EdgesWithGuardMatch edgesWithGuardMatch : matcher.getAllEdgesWithGuard()) {
			// Ha van guard-ja, akkor azt transzformáljuk, és ráírjuk az edge-re
			String guard = UppaalCodeGenerator.transformExpression(edgesWithGuardMatch.getExpression());
			if (builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != null && builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) != "") {
				builder.setEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), builder.getEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition())) + " && " + guard);
			}
			else {					
				builder.setEdgeGuard(transitionEdgeMap.get(edgesWithGuardMatch.getTransition()), guard);
			}		
		}
	}
	
	/**
	 * Létrehozza a template-ek érvényes mûködéséhez szükséges guardokat. (isValid)
	 * @param transition A Yakindu transition, amelynek a megfeleltetett UPPAAL élére rakjuk rá az érvényességi guardot.
	 * @throws IncQueryException 
	 */
	private void createTemplateValidityGuards() throws IncQueryException {
		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
			// Rátesszük a guardokra a template érvényességi vátozót is
			if (builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != null && builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != "") {
				builder.setEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), isActiveVar + " && " + builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())));
			} 
			else {
				builder.setEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), isActiveVar);
			}		
		}
	}
	
	/**
	 * Ez a metódus felel az event raising megvalósításáért.
	 * @throws Exception Jelzi, ha nem mûködik a szinkronizáció. (Nem tökéletes még ez a kód.)
	 */
	private void createRaisingEventSyncs() throws Exception {
		for (EdgesWithRaisingEventMatch edgesWithRaisingEventMatch : matcher.getAllEdgesWithRaisingEvent()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			if (transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()).getSynchronization() != null) {
				throw new Exception("Baj van a raising cuccal, mert már van syncje.");
			}
			builder.setEdgeSync(transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()), syncChanVar + (syncChanId), true);
			for (EdgesWithTriggerElementReferenceMatch edgesWithTriggerElementReferenceMatch : matcher.getAllEdgesWithTriggerElementReference()) {
				if (edgesWithTriggerElementReferenceMatch.getElement() == edgesWithRaisingEventMatch.getElement()) {
					if (transitionEdgeMap.get(edgesWithTriggerElementReferenceMatch.getTransition()).getSynchronization() != null) {
						throw new Exception("Baj van a raising cuccal, mert már van syncje.");
					}
					else {
						builder.setEdgeSync(transitionEdgeMap.get(edgesWithTriggerElementReferenceMatch.getTransition()), syncChanVar + (syncChanId), false);
					}
				}
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
		for (EdgesFromEntryOfParallelRegionsMatch edgesFromEntryOfParallelRegionsMatch : matcher.getEdgesFromEntryOfParallelRegions()) {
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
		for (EdgesWithTimeTriggerMatch edgesWithTimeTriggerMatch : matcher.getEdgesWithTimeTrigger()) {
			builder.setEdgeUpdate(transitionEdgeMap.get(edgesWithTimeTriggerMatch.getIncomingTransition()), clockVar + " = 0");
			builder.setLocationInvariant(stateLocationMap.get(edgesWithTimeTriggerMatch.getSource()), clockVar + " <= " + UppaalCodeGenerator.transformExpression(edgesWithTimeTriggerMatch.getValue()));
			builder.setEdgeGuard(transitionEdgeMap.get(edgesWithTimeTriggerMatch.getTriggerTransition()), clockVar + " >= " + UppaalCodeGenerator.transformExpression(edgesWithTimeTriggerMatch.getValue()));
		}
	}
	
	/**
	 * Ez a metódus létrehozza a triggereket, és a hozzá szükséges új locationöket és edge-eket a megfelelõ template-ekben. 
	 * @throws IncQueryException
	 */
	private void createControlTemplate() throws IncQueryException {
		Template controlTemplate = builder.createTemplate("controlTemplate");
		Location controlLocation = builder.createLocation("triggerLocation", controlTemplate);
		builder.setInitialLocation(controlLocation, controlTemplate);
		Set<String> triggerNames = new HashSet<String>();
		int id = 0;
		for (TriggerOfTransitionMatch triggerOfTransitionMatch : matcher.getAllTriggersOfTransitions()) {			
			if (!triggerNames.contains(triggerOfTransitionMatch.getTriggerName())) {
				Edge ownTriggerEdge = builder.createEdge(controlTemplate);
				builder.setEdgeSource(ownTriggerEdge, controlLocation);
				builder.setEdgeTarget(ownTriggerEdge, controlLocation);
				builder.addGlobalDeclaration("broadcast chan " + triggerOfTransitionMatch.getTriggerName() + ";");
				triggerNames.add(triggerOfTransitionMatch.getTriggerName());
				builder.setEdgeSync(ownTriggerEdge, triggerOfTransitionMatch.getTriggerName(), true);
			}
			if (transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization() != null) {
				Location triggerLocation = builder.createLocation("triggerLocation" + (++id), regionTemplateMap.get(triggerOfTransitionMatch.getParentRegion()));
				builder.setLocationCommitted(triggerLocation);
				Edge syncEdge = builder.createEdge(regionTemplateMap.get(triggerOfTransitionMatch.getParentRegion()));
				builder.setEdgeSource(syncEdge, triggerLocation);
				builder.setEdgeTarget(syncEdge, builder.getEdgeTarget(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition())));
				builder.setEdgeSync(syncEdge, transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()).getSynchronization());
				builder.setEdgeTarget(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerLocation);
				builder.setEdgeSync(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerOfTransitionMatch.getTriggerName(), false);
				hasTriggerPlusEdge.put(triggerOfTransitionMatch.getTransition(), syncEdge);
			}
			else {
				builder.setEdgeSync(transitionEdgeMap.get(triggerOfTransitionMatch.getTransition()), triggerOfTransitionMatch.getTriggerName(), false);
			}
		}
	}
	
}
