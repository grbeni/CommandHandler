package inc;

import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelBuilder; 
import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelSaver;
import hu.bme.mit.inf.alf.uppaal.transformation.serialization.UppaalModelSerializer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.yakindu.sct.model.sgraph.Choice;
import org.yakindu.sct.model.sgraph.Entry;
import org.yakindu.sct.model.sgraph.Reaction;
import org.yakindu.sct.model.sgraph.Region;
import org.yakindu.sct.model.sgraph.State;
import org.yakindu.sct.model.sgraph.Statechart;
import org.yakindu.sct.model.sgraph.Transition;
import org.yakindu.sct.model.sgraph.Vertex;
import org.yakindu.sct.model.stext.stext.EntryEvent;
import org.yakindu.sct.model.stext.stext.ExitEvent;
import org.yakindu.sct.model.stext.stext.ReactionEffect;
import org.yakindu.sct.model.stext.stext.ReactionTrigger;

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
	private final String isValidVar = "isValid";
	
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
	
	// Szinkronizációs csatornák létrehozására
	private int syncChanId = 0;

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
									
									// ID változók resetelése
									syncChanId = 0;
									
									// Változók berakása
									createVariables();
									
									// Template-ek létrehozása
									createTemplates();																											
																											
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
		System.out.println(allVariableDefinitions.size());
		for (VariableDefinitionsMatch variableMatch : allVariableDefinitions) {
			String expression = "";
			if (variableMatch.getIsReadonly()) {
				expression = expression + "const ";
			}
			if (variableMatch.getType().getName() == "integer") { 
				expression = expression + "int ";
			}
			else if (variableMatch.getType().getName() == "boolean") {
				expression = expression + "bool ";
			}
			expression = expression + variableMatch.getName() + " ";
			if (variableMatch.getVariable().getInitialValue() == null) {
				expression = expression + ";";
				builder.addGlobalDeclaration(expression);
			}
			else {
				builder.addGlobalDeclaration(expression + "=" + UppaalCodeGenerator.transformExpression(variableMatch.getVariable().getInitialValue()) + ";");
			}			
		}
	}
	
	/**
	 * Ez a metódus létrehozza az egyes UPPAAL template-eket a Yakindu regionök alapján.
	 * @throws IncQueryException
	 */
	private void createTemplates() throws IncQueryException {
		// Lekérjük a régiókat
		Collection<RegionsMatch> regionMatches = matcher.getRegions();
		// Végigmegyünk a régiókon, és létrehozzuk a Yakindu modellnek megfeleltethetõ elemeket.
		for (RegionsMatch regionMatch : regionMatches) {
			Template template = null;			
			// Kiszedjük a template nevekbõl a szóközöket, mert az UPPAAL nem szereti
			if (isTopRegion(regionMatch.getRegion())) {
				template = builder.createTemplate(regionMatch.getRegionName().replaceAll(" ", "") + "OfStatechart");
				// Mégis foglalkozunk, hogy a regionökön átívelõ tranziciók helyes lefutása garantálható legyen
				builder.addLocalDeclaration("bool " + isValidVar + " = true;", template);
			} 
			else {
				template = builder.createTemplate(regionMatch.getRegionName().replaceAll(" ", "") + "Of" + ((State) regionMatch.getRegion().getComposite()).getName());
				// Az alsóbb szinteken kezdetben false érvényességi változót vezetünk be
				builder.addLocalDeclaration("bool " + isValidVar + " = false;", template);
			}			
			
			// A region-template párokat berakjuk a Mapbe
			regionTemplateMap.put(regionMatch.getRegion(), template);
										   									
			//Kiinduló állapotot beállítjuk									 
			Location entryLocation = builder.createLocation(regionMatch.getEntry().getKind().getName(), template);									
			template.setInit(entryLocation);
			builder.setLocationComment(entryLocation, "Initial entry node");
			// Az entry node legfelsõ szinten committed, alsóbb szinteken urgent, hogy ne legyen baj a deadlock-kal, ha committed állapotól kijövõ éleken van guard
			if (isTopRegion(regionMatch.getRegion())) {
				builder.setLocationCommitted(entryLocation);		
			}
			else {
				// Ez nem tökéletes így, inkább valamiféle committed kéne, de az nem megvalósítható
				builder.setLocationUrgent(entryLocation);	
			}
			
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
		
		// Beállítjuk a composite state-ek exit transitionjeit, hogy minden kimenetjir minden alrégió helyes állapotba kerüljön (false)
		createExitEdgesForAbstractionLevels();
		
		// Beállítjuk azon csatornákat, amelyek különbözõ absztakciós szintek közötti tranziciókat vezérlik
		// Valamiért a Yakindu/Eclipse szórakozik ekkor
		createEdgesForDifferentAbstraction();

		// Edge effectek berakása
		setEdgeUpdates();
		
		// Edge guardok berakása
		setEdgeGuards();		
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
				if (isCompositeState(stateMatch.getState())) {
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
			for (Transition transition : exitNodesMatch.getExit().getIncomingTransitions()) {							
				builder.setEdgeSync(transitionEdgeMap.get(transition), syncChanVar + (syncChanId), true);
				builder.setEdgeUpdate(transitionEdgeMap.get(transition), isValidVar + " = false");
				// Guardot nem állítunk, azt majd a közös metódusban
				builder.setEdgeComment(transitionEdgeMap.get(transition), "Exit node-ba vezeto el, kilep a templatebol.");
			}
			// Letiltjuk az összes felette lévõ régiót, ehhez lekérjük a felette lévõ regionöket
			List<Region> regionList = new ArrayList<Region>();
			regionList = getThisAndUpperRegions(regionList, exitNodesMatch.getRegion());
			// Kivesszük a saját regionét
			regionList.remove(exitNodesMatch.getRegion());
			// Letiltjuk a régiókat
			setAllRegionsWithSync(false, regionList);
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
			Location stateEntryLocation = builder.createLocation("CompositeStateEntry", regionTemplateMap.get(compositeStateMatch.getParentRegion()));
			builder.setLocationCommitted(stateEntryLocation);
			Edge entryEdge = builder.createEdge(regionTemplateMap.get(compositeStateMatch.getParentRegion()));
			builder.setEdgeSource(entryEdge, stateEntryLocation);
			builder.setEdgeTarget(entryEdge, stateLocationMap.get(compositeStateMatch.getCompositeState()));
			// Berakjuk a state-edge párt a map-be
			hasEntryLoc.put(compositeStateMatch.getCompositeState(), entryEdge);
			// Átállítjuk a bejövõ élek targetjét, ehhez felhasználjuk az összes élet lekérdezõ metódust
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
				if (sourceAndTargetOfTransitionsMatch.getTarget() == compositeStateMatch.getCompositeState()) {
					transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).setTarget(stateEntryLocation);
				}
			}
		}
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
		for (CompositeStatesMatch compositeStateMatch : allCompositeStatesMatches) { {
				builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
				Edge entryEdge = hasEntryLoc.get(compositeStateMatch.getCompositeState());
				builder.setEdgeSync(entryEdge, syncChanVar + (syncChanId), true);
				// Minden eggyel alatti régióban létrehozzuk a szükséges ? sync-eket
				for (RegionsOfCompositeStatesMatch regionsOfCompositeStatesMatch : matcher.getAllRegionsOfCompositeStates()) {
					if (regionsOfCompositeStatesMatch.getCompositeState() == compositeStateMatch.getCompositeState()) {
						// Ha van historyja: önmagába (vagy composite-nál az entryLocationbe) kötjük
						if (getEntryOfRegion(regionsOfCompositeStatesMatch.getSubregion()).getKind().getValue() != 0 || hasDeepHistoryAbove(regionsOfCompositeStatesMatch.getSubregion())) {
							for (VerticesOfRegionsMatch verticesOfRegionMatch : matcher.getAllVerticesOfRegions()) {
								if (verticesOfRegionMatch.getRegion() == regionsOfCompositeStatesMatch.getSubregion()) {
									// Csak ha nem choice
									if (!(isChoice(verticesOfRegionMatch.getVertex()))) {
										Edge toItselfEdge = builder.createEdge(regionTemplateMap.get(verticesOfRegionMatch.getRegion()));
										builder.setEdgeSync(toItselfEdge, syncChanVar + syncChanId, false);
										builder.setEdgeUpdate(toItselfEdge, isValidVar + " = true");
										builder.setEdgeSource(toItselfEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
										if (hasEntryLoc.containsKey(verticesOfRegionMatch.getVertex())) {	
											builder.setEdgeTarget(toItselfEdge, hasEntryLoc.get(verticesOfRegionMatch.getVertex()).getSource());
										}
										else {
											builder.setEdgeTarget(toItselfEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
										}
									}
								}
							}
						}
						// Ha nincs historyja: region entry-be kötjük
						else {
							for (VerticesOfRegionsMatch verticesOfRegionMatch : matcher.getAllVerticesOfRegions()) {
								if (verticesOfRegionMatch.getRegion() == regionsOfCompositeStatesMatch.getSubregion()) {
									Edge toEntryEdge = builder.createEdge(regionTemplateMap.get(verticesOfRegionMatch.getRegion()));
									builder.setEdgeSync(toEntryEdge, syncChanVar + syncChanId, false);
									builder.setEdgeUpdate(toEntryEdge, isValidVar + " = true");
									builder.setEdgeSource(toEntryEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
									builder.setEdgeTarget(toEntryEdge, stateLocationMap.get(getEntryOfRegion(verticesOfRegionMatch.getRegion())));
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Ez a metódus hozza létre a composite state-ekbõl kivezetõ éleken a broadcast ! szinkronizációs csatornát,
	 * és minden alatta lévõ régióban a ? szinkronizációs csatornákat. Utóbbi esetben a csatorna mindig önmagába vezet.
	 * @throws IncQueryException
	 */
	private void createExitEdgesForAbstractionLevels() throws IncQueryException {
		// Lekérjük az állapotokat
		Collection<StatesMatch> allStateMatches = matcher.getAllStates();
		// Megnézzük az összes state matchet
		for (StatesMatch stateMatch : allStateMatches) {
			State state = stateMatch.getState();
			if (state.isComposite()) {
				builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
				// Minden kimenõ élre ráírjuk a kilépési sync-et
				for (Transition transition : transitionEdgeMap.keySet()) {
					if (state.getOutgoingTransitions().contains(transition)) {
						builder.setEdgeSync(transitionEdgeMap.get(transition), syncChanVar + (syncChanId), true);
					}
				}
				// Letiltjuk az összes alatta lévõ region-t
				List<Region> subregionList = new ArrayList<Region>();
				addAllSubregionsToRegionList(state, subregionList);
				setAllRegionsWithSync(false, subregionList);
				
			}
		}
	}
	
	/**
	 * Minden megadott régióban létrehozza a ? szinkronizációs csatornákat, és azokon az érvényességi változók updatejeit.
	 * Ezek a csatornák mindig önmagukba vezetnek.
	 * @param toBeTrue Engedélyezni vagy tiltani szeretnénk-e a régiókat.
	 * @param regionList Yakindu regionök listája, amelyeken létre szeretnénk hozni a ? csatornákat az update-ekkel.
	 */
	private void setAllRegionsWithSync(boolean toBeTrue, List<Region> regionList) {
			for (Region subregion : regionList) {
				for (Vertex vertex : subregion.getVertices()) {
					// Choice-okból nem csinálunk magukba éleket, azokban elvileg nem tartózkodhatunk
					if (!(vertex instanceof Choice)) {
						Edge toItselfEdge = builder.createEdge(regionTemplateMap.get(subregion));
						builder.setEdgeSync(toItselfEdge, syncChanVar + syncChanId, false);
						builder.setEdgeUpdate(toItselfEdge, isValidVar + " = " + ((toBeTrue) ? "true" : "false"));
						builder.setEdgeSource(toItselfEdge, stateLocationMap.get(vertex));
						builder.setEdgeTarget(toItselfEdge, stateLocationMap.get(vertex));
					}
				}
			}
	}
	
	/**
	 * Ez a metódus paramétersoron visszaadja a megadott state összes alatta lévõ region-jét. (Nem csak az eggyel alatta lévõket.)
	 * @param state Yakindu composite state, amelynek az összes alatta lévõ regionje kell.
	 * @param regionList Ebbe a listába fogja betenni a metódus a regionöket.
	 */
	private void addAllSubregionsToRegionList(State state, List<Region> regionList) {
		if (state.getRegions() != null) {
			for (Region region : state.getRegions()) {
				regionList.add(region);
				for (Vertex vertex : region.getVertices()) {
					if (vertex instanceof State) {
						State subState = (State) vertex;
						if (subState.isComposite()) {
							addAllSubregionsToRegionList(subState, regionList);
						}
					}
				}				
			}
		}
	}	
	
	/**
	 * Visszaadja, hogy található-e a regionben vagy felette deep history indicator.
	 * @param region Yakindu region, amely felett keressük a deep history indicatort.
	 * @return Van-e a regionben, vagy felette deep history indicator. 
	 */
	private boolean hasDeepHistoryAbove(Region region) {
		if (region.getComposite() instanceof Statechart) {
			return false;
		}
		else {
			State parentState = (State) region.getComposite();
			return ((getEntryOfRegion(parentState.getParentRegion()).getKind().getValue() == 2) || hasDeepHistoryAbove(parentState.getParentRegion()));
		}
	}
	
	/**
	 * Ez a metódus visszaadja egy adott region entry elemét.
	 * Feltételezi, hogy csak egy ilyen van egy region-ben. (Különben a Yakindu modell hibás.)
	 * @param region A Yakindu region, amelyben keressük az entry.
	 * @return A Yakindu entry elem.
	 */
	private Entry getEntryOfRegion(Region region) {
		for (Vertex vertex : region.getVertices()) {
			if (vertex instanceof Entry) {
				return ((Entry) vertex);
			}
		}
		return null;
	}
	
	/**
	 * Létrehozza azokat az éleket, amelyeknek végpontjai nem ugyanazon regionben találhatók.
	 * @throws IncQueryException
	 */
	private void createEdgesForDifferentAbstraction() throws IncQueryException {
		//Lekérjük a transition match-eket		
				Collection<SourceAndTargetOfTransitionsMatch> allTransitionMatches = matcher.getAllTransitions();	
				// Megnézzük a transition match-eket és létrehozzuk az edge-eket a megfelelõ guardokkal és effectekkel
				for (SourceAndTargetOfTransitionsMatch transitionMatch : allTransitionMatches) {											
					// Ha a két végpont nem azonos region-ben van:
					// Megnézzük melyik milyen szintû, és aszerint hozzuk létre a szinkronizációs csatornákat
					if (transitionMatch.getSource().getParentRegion() != transitionMatch.getTarget().getParentRegion()
							&& stateLocationMap.containsKey(transitionMatch.getSource()) && stateLocationMap.containsKey(transitionMatch.getTarget())) {								
						int sourceLevel = getLevelOfVertex(transitionMatch.getSource());
						int targetLevel = getLevelOfVertex(transitionMatch.getTarget());
						if (sourceLevel < targetLevel) {
							createEdgesWhenSourceLesser(transitionMatch.getSource(), transitionMatch.getTarget(), transitionMatch.getTransition(), targetLevel, targetLevel - sourceLevel, new ArrayList<Region>());							
						}						
						if (sourceLevel > targetLevel) {
							createEdgesWhenSourceGreater(transitionMatch.getSource(), transitionMatch.getTarget(), transitionMatch.getTransition(), sourceLevel);
						}
					}				
				}
	}
	
	/**
	 * Visszaadja, hogy egy vertex elem milyen messze található a legfölsõ regiontõl.
	 * (Ha a legfölsõ regionben található, akkor ez az érték 0.)
	 * @param vertex A Yakindu vertex, amelynek lekérjük a szintjét.
	 * @return A szint mint egész szám.
	 */
	private int getLevelOfVertex(Vertex vertex) {
		if (vertex.getParentRegion().getComposite() instanceof Statechart) {
			return 0;
		}
		else if (vertex.getParentRegion().getComposite() instanceof State) {
			return getLevelOfVertex((Vertex) vertex.getParentRegion().getComposite()) + 1;
		} 
		else {
			throw new IllegalArgumentException("A " + vertex.toString() + " compositeja nem State és nem Statechart.");
		}
	}
	
	/**
	 * Létrehozza az absztrakciós szintek közötti tranziciókhoz szükséges éleket.
	 * Csak akkor mûködik, ha a source szintje kisebb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzició kezdõpontja.
	 * @param target Yakindu vertex, a tranzició végpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfelsõ szinten létrehozott edget.
	 * @param lastLevel Egész szám, amely megmondja, hogy a target hányadik szinten van.
	 */ 
	private void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions) {
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
			// Ez az él felel majd meg a regionökön átívelõ transitionnek
			transitionEdgeMap.put(transition, abstractionEdge);
			// Ha a target composite state, akkor belépésre minden alrégiójába is belépünk
			if (target instanceof State) {
				State targetState = (State) target;
				if (targetState.isComposite()) {
					List<Region> pickedSubregions = new ArrayList<Region>(targetState.getRegions()); // Talán addAll kéne?
					pickedSubregions.removeAll(visitedRegions);
					setAllRegionsWithSync(true, pickedSubregions);
				}
			}
		}
		// Ha nem a legfölsõ szinten vagyunk, akkor létrehozzuk a ? szinkronizációs éleket minden állapotból a megfelelõ állapotba
		else {
			for (Vertex vertex : target.getParentRegion().getVertices()) {
				Edge syncEdge = builder.createEdge(regionTemplateMap.get(vertex.getParentRegion()));
				builder.setEdgeSource(syncEdge, stateLocationMap.get(vertex));				
				builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));				
				builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), false);
				builder.setEdgeUpdate(syncEdge, isValidVar + " = true");				
			}			
			// Ha a target composite state, akkor ezt minden region-jére megismételjük, kivéve ezt a regiont
			if (target instanceof State) {
				State targetState = (State) target;
				if (targetState.isComposite()) {
					List<Region> pickedSubregions = new ArrayList<Region>(targetState.getRegions()); // Talán addAll kéne?
					pickedSubregions.removeAll(visitedRegions);
					setAllRegionsWithSync(true, pickedSubregions);
				}
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
	 */
	private void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel) {
		// A legalsó szinten létrehozunk egy magába vezetõ élet: 
		// Ez felel meg az alacsonyabb szintrõl magasabb szinten lévõ vertexbe vezetõ átmenetnek
		if (getLevelOfVertex(source) == lastLevel) {
			// Létrehozunk egy szinkronizációs csatornát rá
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), true);
			// Letiltjuk ezt a régiót, mert önmagára nem tud szinkronizálni
			builder.setEdgeUpdate(ownSyncEdge, isValidVar + " = false");
			builder.setEdgeComment(ownSyncEdge, "A Yakinduban magasabb absztrakcios szinten levo vertexbe vezeto el.");
			// Ez az él felel majd meg a regionökön átívelõ transitionnek
			transitionEdgeMap.put(transition, ownSyncEdge);
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel);
		}
		// A felsõ szint
		else if (getLevelOfVertex(source) == getLevelOfVertex(target)) {
			// A felsõ szinten létrehozzuk az élet, amely fogadja a szinkronizációt
			Edge ownsyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownsyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownsyncEdge, stateLocationMap.get(target));
			builder.setEdgeSync(ownsyncEdge, syncChanVar + (syncChanId), false);
			// Itt letiltjuk az összes source alatt lévõ régiót, jelezve, hogy azok már nem érvényesek
			List<Region> subregionList = new ArrayList<Region>();
			State sourceState = (State) source;
			addAllSubregionsToRegionList(sourceState, subregionList);
			setAllRegionsWithSync(false, subregionList);
			return;
		}
		// Közbülsõ szinteken csak rekurzívan lépegetünk fel
		else {
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel);
		}
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eken az update-eket a Yakindu effectek alapján.
	 */
	private void setEdgeUpdates() {
		// Végigmegyünk minden transition-ön
		for (Transition transition : transitionEdgeMap.keySet()) {		
			// Ha van effectje, akkor azt transzformáljuk, és ráírjuk az edge-re
			if ((transition.getEffect() != null) && (transition.getEffect() instanceof ReactionEffect)) {
				String effect = UppaalCodeGenerator.transformEffect((ReactionEffect) transition.getEffect());
				builder.setEdgeUpdate(transitionEdgeMap.get(transition), effect);
			}
		}
		// Megcsiáljuk a state update-eket is
		setEdgeUpdatesFromStates();
	}
	
	/**
	 * Ez a metódus felel az egyes state-ek entry/exit triggerjeinek hatásaiért.
	 * Minden Entry triggerrel rendelkezõ state esetén létrehoz egy committed location-t,
	 * amelybõl a kivezetõ él a megfelelõ locationba vezet, és tartalmazza a szükséges update-eket.
	 * Minden Exit triggerrel rendelkezõ state esetén a kimenõ élekre rakja rá a szükséges update-eket.
	 * Könnyen belátható, hogy Exit esetén nem mûködne az Entry-s megoldás.
	 */
	private void setEdgeUpdatesFromStates() {
		// Végigmegyünk minden vertex-en
		for (Vertex vertex : stateLocationMap.keySet()) {
			// Ha a vertex egy state, akkor lehetnek  local reaction-jei
			if (vertex instanceof State) {
				State state = (State) vertex;
				// Végigmegyünk minden reaction-ön
				for (Reaction reaction : state.getLocalReactions()) {
					// Ha ReactionEffect, akkor azt ráírjuk minden bejövõ Edge-re
					if ((reaction.getTrigger() instanceof ReactionTrigger) && (reaction.getEffect() instanceof ReactionEffect)) {
						ReactionTrigger reactionTrigger = (ReactionTrigger) reaction.getTrigger();
						// Ha Entry, akkor létrehozunk egy committed állapotot, amelybõl egy élet vezetünk a megfelelõ location-bea megfelelõ update-tel
						if (reactionTrigger.getTriggers().get(0) instanceof EntryEvent) {
							// Transzformáljuk a kifejezést
							String effect = UppaalCodeGenerator.transformEffect((ReactionEffect) reaction.getEffect());
							// Ha nincs még entry state-je
							if (!hasEntryLoc.containsKey(state)) {
								// Létrehozzuk a locationt, majd a megfelelõ éleket a megfelelõ location-ökbe kötjük
								Location stateEntryLocation = builder.createLocation("StateEntryLocation", regionTemplateMap.get(state.getParentRegion()));
								builder.setLocationCommitted(stateEntryLocation);
								Edge entryEdge = builder.createEdge(regionTemplateMap.get(state.getParentRegion()));
								builder.setEdgeSource(entryEdge, stateEntryLocation);
								builder.setEdgeTarget(entryEdge, stateLocationMap.get(state));
								builder.setEdgeUpdate(entryEdge, effect);
								// Átállítjuk a bejövõ élek targetjét
								for (Transition transition : transitionEdgeMap.keySet()) {
									if (state.getIncomingTransitions().contains(transition)) {
										transitionEdgeMap.get(transition).setTarget(stateEntryLocation);
									}
								}	
							}
							// Ha már van entry state-je
							else {
								builder.setEdgeUpdate(hasEntryLoc.get(state), effect);
							}
						}						
						// Ha Exit, akkor ráírjuk az update-et minden kimenõ élre
						// Nem használhatunk committed locationt
						else if (reactionTrigger.getTriggers().get(0) instanceof ExitEvent) {
							// Transzformáljuk a kifejezést
							String effect = UppaalCodeGenerator.transformEffect((ReactionEffect) reaction.getEffect());				
							// Nem hozhatunk létre egy új committed locationt
							// Mégis ez a helyes, hiába nem annyira látványos
							for (Transition transition : transitionEdgeMap.keySet()) {
								if (state.getOutgoingTransitions().contains(transition)) {
									builder.setEdgeUpdate(transitionEdgeMap.get(transition), effect);
								}
							}
						}
					}
				}
			}
		}		
	}
	
	/**
	 * Ez a metódus létrehozza az UPPAAL edge-eken az guardokat a Yakindu guardok alapján.
	 */
	private void setEdgeGuards() {
		// Végigmegyünk minden transition-ön
		for (Transition transition : transitionEdgeMap.keySet()) {
			// Ha van guard-ja, akkor azt transzformáljuk, és ráírjuk az edge-re
			if ((transition.getTrigger() != null) && (transition.getTrigger() instanceof ReactionTrigger)) {
				String guard = UppaalCodeGenerator.transformGuard((ReactionTrigger) transition.getTrigger());
				if (builder.getEdgeGuard(transitionEdgeMap.get(transition)) != null && builder.getEdgeGuard(transitionEdgeMap.get(transition)) != "") {
					builder.setEdgeGuard(transitionEdgeMap.get(transition), builder.getEdgeGuard(transitionEdgeMap.get(transition)) + " && " + guard);
				}
				else {					
					builder.setEdgeGuard(transitionEdgeMap.get(transition), guard);
				}
			}
			// Felrakjuk az érvényességi változókat is minden élre
			createTemplateValidityGuards(transition);			
		}
	}
	
	/**
	 * Létrehozza a template-ek érvényes mûködéséhez szükséges guardokat. (isValid)
	 * @param transition A Yakindu transition, amelynek a megfeleltetett UPPAAL élére rakjuk rá az érvényességi guardot.
	 */
	private void createTemplateValidityGuards(Transition transition) {
		// Rátesszük a guardokra a template érvényességi vátozót is
		if (builder.getEdgeGuard(transitionEdgeMap.get(transition)) != null && builder.getEdgeGuard(transitionEdgeMap.get(transition)) != "") {
			builder.setEdgeGuard(transitionEdgeMap.get(transition), isValidVar + " && " + builder.getEdgeGuard(transitionEdgeMap.get(transition)));
		} 
		else {
			builder.setEdgeGuard(transitionEdgeMap.get(transition), isValidVar);
		}			
	}
	
	/**
	 * Visszaadja a regiont, és a felette lévõ faszerkezetben elhelyezkedõ regionöket.
	 * @param regionList Eleinte üres lista, amelyben tárolni fogja a regionöket.
	 * @param region Ettõl a Yakindu region-tõl kezde indulunk el felfelé, és tároljuk el a regionöket.
	 * @return Egy lista a faszerkezetben lévõ regionökrõl
	 */
	private List<Region> getThisAndUpperRegions(List<Region> regionList, Region region) {
		regionList.add(region);
		if (region.getComposite() instanceof Statechart) {
			return regionList;
		}
		else {		
			return getThisAndUpperRegions(regionList, ((State) region.getComposite()).getParentRegion());
		}
	}
	
	private boolean isTopRegion(Region region) throws IncQueryException {
		for (TopRegionsMatch topRegionMatch : matcher.getTopRegions()) {
			if (topRegionMatch.getRegion() == region) {
				return true;
			}
		}
		return false;
	}
	
	private boolean isCompositeState(Vertex vertex) throws IncQueryException {
		for (CompositeStatesMatch compositeStatesMatch : matcher.getAllCompositeStates()) {
			if (compositeStatesMatch.getCompositeState() == vertex) {
				return true;
			}
		}
		return false;
	}
	
	private boolean isChoice(Vertex vertex) throws IncQueryException {
		for (ChoicesMatch choicesMatch : matcher.getAllChoices()) {
			if (choicesMatch.getChoice() == vertex) {
				return true;
			}
		}
		return false;
	}

}
