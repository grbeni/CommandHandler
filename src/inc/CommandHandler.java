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
 * Az oszt�ly, amely a Yakindu p�ld�nymodell alapj�n l�trehozza az UPPAAL p�ld�nymodellt.
 * F�gg a PatternMatcher �s az UppaalModelBuilder oszt�lyokt�l.
 * @author Graics Bence 
 * Kell m�g:
 * -Synchronization node?
 * -Id�m�r�s?
 * 
 * Location-�k:
 *  - vertexekb�l
 *  - state entry trigger eset�n committed location: entryOfB-> nincs hozz� vertex
 * Edge-ek:
 *  - transition-�kb�l
 *  - composite state bel�p� �lei: legfels� ! �l, eggyel alatta ? �lek isValidVar + " = true" -val
 *  - composite state kil�p� �lei: legfels� ! �l, minden alatta l�v� r�gi�ban ? �lek isValidVar + " = false" -szal
 *  - k�l�nb�z� absztrakci�s szinteket �sszek�t� �lek
 */
public class CommandHandler extends AbstractHandler {
	 
	// Uppaal v�ltoz�nevek
	private final String syncChanVar = "syncChan";
	private final String isActiveVar = "isActive";
	private final String clockVar = "Timer";
			
	// Az IncQuery illeszked�sek lek�r�s�re
	private PatternMatcher matcher = null;
			
	// Az UPPAAL modell fel�p�t�sre
	private UppaalModelBuilder builder = null;	
			
	// Egy Map a Yakindu:Region -> UPPAAL:Template lek�pz�sre									 								
	private Map<Region, Template> regionTemplateMap = null;
			
	// Egy Map a Yakindu:Vertex -> UPPAAL:Location lek�pz�sre									 								
	private Map<Vertex, Location> stateLocationMap = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge lek�pz�sre
	private Map<Transition, Edge> transitionEdgeMap = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge lek�pz�sre
	private Map<Vertex, Edge> hasEntryLoc = null;
	
	// Egy Map, amely t�rolja az egyes Vertexek triggerLocation kimen� �l�t
	private Map<Transition, Edge> hasTriggerPlusEdge = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge lek�pz�sre
	private Map<Vertex, Edge> hasExitLoc = null;
	
	// Egy Map, amely t�rolja az altemplate-ek "initial location"-j�t
	private Map<Template, Location> hasInitLoc = null;
			
	// Szinkroniz�ci�s csatorn�k l�trehoz�s�ra
	private int syncChanId = 0;
	// EntryLoc n�v gener�l�sra
	private int entryStateId = 0;
	// ExitLoc n�v gener�l�sra
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
								matcher.setResource(resource); // IncQuery engine inicializ�ci� a resource-ra: statechartot tartalmaz� f�jl
								
								try {
									// UPPAAL modell inicializ�ci�
									builder = UppaalModelBuilder.getInstance();
									builder.createNTA(statechart.getName());
									
									// Map-ek inicializ�l�sa
									regionTemplateMap = new HashMap<Region, Template>();									 								
									stateLocationMap = new HashMap<Vertex, Location>();
									transitionEdgeMap = new HashMap<Transition, Edge>();
									hasEntryLoc = new HashMap<Vertex, Edge>();
									hasTriggerPlusEdge = new HashMap<Transition, Edge>();
									hasExitLoc = new HashMap<Vertex, Edge>();
									hasInitLoc = new HashMap<Template, Location>();
									
									// ID v�ltoz�k resetel�se
									syncChanId = 0;
									entryStateId = 0;
									exitStateId = 0;
									
									// V�ltoz�k berak�sa
									createVariables();
									
									// Template-ek l�trehoz�sa
									createTemplates();	
									
									// Triggerek felv�tele
									createControlTemplate();
																											
									// Fel�p�ti az UPPAAL modellt a berakott elemekb�l
									builder.buildModel();

									// L�trehozza a SampleRefelcetiveEcoreEditorral megnyithat� UPPAAL modellt
									builder.saveUppaalModel(fileURISubstring);									
									
									String filen = UppaalModelSaver.removeFileExtension(fileURISubstring);									
									// Elmenti a modellt egy XML f�jlba, l�nyeg�ben l�trehozza az UPPAAL �ltal megnyithat� f�jlt
									UppaalModelSerializer.saveToXML(filen);
									
									// Reseteli a buildert, hogy a k�vetkez� transzform�ci�t null�r�l kezdhess�k
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
	 * Ez a met�dus l�trehozza az UPPAAL v�ltoz�kat a Yakindu v�ltoz�k alapj�n.
	 * @throws IncQueryException
	 */
	private void createVariables() throws IncQueryException {
		// Lek�rj�k a v�ltoz� definici�kat
		Collection<VariableDefinitionsMatch> allVariableDefinitions = matcher.getAllVariables();
		System.out.println("A v�ltoz�k sz�ma: " + allVariableDefinitions.size());
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
	 * Ez a met�dus l�trehozza az egyes UPPAAL template-eket a Yakindu region�k alapj�n.
	 * @throws Exception 
	 */
	private void createTemplates() throws Exception {
		// Lek�rj�k a r�gi�kat
		Collection<EntryOfRegionsMatch> regionMatches = matcher.getAllRegionsWithEntry();
		// V�gigmegy�nk a r�gi�kon, �s l�trehozzuk a Yakindu modellnek megfeleltethet� elemeket.
		for (EntryOfRegionsMatch regionMatch : regionMatches) {
			Template template = null;			
			// Kiszedj�k a template nevekb�l a sz�k�z�ket, mert az UPPAAL nem szereti
			if (Helper.isTopRegion(regionMatch.getRegion())) {
				template = builder.createTemplate(regionMatch.getRegionName().replaceAll(" ", "") + "OfStatechart");
				// M�gis foglalkozunk, hogy a region�k�n �t�vel� tranzici�k helyes lefut�sa garant�lhat� legyen
				builder.addLocalDeclaration("bool " + isActiveVar + " = true;", template);
			} 
			else {
				template = builder.createTemplate(regionMatch.getRegionName().replaceAll(" ", "") + "Of" + ((State) regionMatch.getRegion().getComposite()).getName());
				// Az als�bb szinteken kezdetben false �rv�nyess�gi v�ltoz�t vezet�nk be
				builder.addLocalDeclaration("bool " + isActiveVar + " = false;", template);
			}			
			// Betesz�nk egy clockot
			builder.addLocalDeclaration("clock " + clockVar + ";", template);
			
			// A region-template p�rokat berakjuk a Mapbe
			regionTemplateMap.put(regionMatch.getRegion(), template);
										   									
			//Kiindul� �llapotot be�ll�tjuk									 
			Location entryLocation = builder.createLocation(regionMatch.getEntry().getKind().getName(), template);
			builder.setInitialLocation(entryLocation, template);
			// Az entry node committed
			builder.setLocationCommitted(entryLocation);
			builder.setLocationComment(entryLocation, "Initial entry node");
	
			//Betessz�k a kezd��llapotot a Map-be									 
			stateLocationMap.put(regionMatch.getEntry(), entryLocation);
		
			// L�trehozzuk a location-�ket a state-ekb�l
			createLocationsFromStates(regionMatch.getRegion(), template);
			
			// L�trehozzuk a location-�ket a choice-okb�l
			createLocationsFromChoices(regionMatch.getRegion(), template);
			
			// L�trehozzuk a location-�ket a final state-ekb�l
			createLocationsFromFinalStates(regionMatch.getRegion(), template);
			
			// L�trehozzuk a location-�ket az exit node-okb�l
			createLocationsFromExitNodes(regionMatch.getRegion(), template);			
			
			// L�trehozzuk az edge-eket a transition-�kb�l																			
			createEdges(regionMatch.getRegion(), template);			
		}	
		
		// Exit node-ok syncjeinek l�trehoz�sa
		createUpdatesForExitNodes();
		
		// Composite �llapotok entry statej�nek l�trehoz�sa
		createEntryForCompositeStates();
		
		// Be�ll�tjuk a composite state-ek entry transitionjeit, hogy minden bemenetkor minden alr�gi� helyes �llapotba ker�lj�n (kezd��llapot/�nmaga, true)
		createEntryEdgesForAbstractionLevels();
		
		// Be�ll�tjuk a composite state-ek exit transitionjeit, hogy minden kimenetkor minden alr�gi� helyes �llapotba ker�lj�n (false)
		createExitEdgesForAbstractionLevels();
		
		// Be�ll�tjuk azon csatorn�kat, amelyek k�l�nb�z� absztakci�s szintek k�z�tti tranzici�kat vez�rlik
		createEdgesForDifferentAbstraction();

		// Edge effectek berak�sa
		setEdgeUpdates();
		
		// Edge guardok berak�sa
		setEdgeGuards();		
		
		// Template guardok berak�sa
		createTemplateValidityGuards();
		
		// Entry kimen� �lek beSyncel�se
		createSyncFromEntries();
		
		// After .. kifejez�sek transzform�l�sa
		createTimingEvents();
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL location�ket a Yakindu state-ek alapj�n a StatesMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a state-ek val�k.
	 * @param template Az UPPAAL template, amelybe a location�ket kell rakni.
	 * @throws IncQueryException
	 */
	private void createLocationsFromStates(Region region, Template template) throws IncQueryException {
		// Lek�rj�k az �llapotokat
		Collection<StatesMatch> allStateMatches = matcher.getAllStates();
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket
		for (StatesMatch stateMatch : allStateMatches) {				
			if (stateMatch.getParentRegion() == region) {										
				Location aLocation = builder.createLocation(stateMatch.getName(), template);
				stateLocationMap.put(stateMatch.getState(), aLocation); // A state-location p�rokat betessz�k a map-be	
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
	 * Ez a met�dus l�trehozza az UPPAAL location�ket a Yakindu choice-ok alapj�n a ChoiceMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a choice-ok val�k.
	 * @param template Az UPPAAL template, amelybe a location�ket kell rakni.
	 * @throws IncQueryException
	 */
	private void createLocationsFromChoices(Region region, Template template) throws IncQueryException {
		// A k�l�nb�z� choice-ok megk�l�nb�ztet�s�re
		int id = 0; 
		// Lek�rj�k a choice-okat
		Collection<ChoicesMatch> allChoices = matcher.getAllChoices();
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket		
		for (ChoicesMatch choiceMatch : allChoices) {
			if (choiceMatch.getRegion() == region) {										
				Location aLocation = builder.createLocation("Choice" + id++, template);
				builder.setLocationCommitted(aLocation);
				stateLocationMap.put(choiceMatch.getChoice(), aLocation); // A choice-location p�rokat betessz�k a map-be	
				builder.setLocationComment(aLocation, "A choice");
			}
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL location�ket a Yakindu final state-ek alapj�n a FinalStatesMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a final state-ek val�k.
	 * @param template Az UPPAAL template, amelybe a location�ket kell rakni.
	 * @throws IncQueryException
	 */
	private void createLocationsFromFinalStates(Region region, Template template) throws IncQueryException {
		// A k�l�nb�z� final state-ek megk�l�nb�ztet�s�re
		int id = 0; 
		// Lek�rj�k a final state-eket
		Collection<FinalStatesMatch> allFinalStates = matcher.getAllFinalStates();
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket		
		for (FinalStatesMatch finalStateMatch : allFinalStates) {					
			if (finalStateMatch.getRegion() == region) {										
				Location aLocation = builder.createLocation("FinalState" + id++, template);
				stateLocationMap.put(finalStateMatch.getFinalState(), aLocation); // A final state-location p�rokat betessz�k a map-be	
				builder.setLocationComment(aLocation, "A final state");
			}
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL location�ket a Yakindu exit node-ok alapj�n az ExitNodesMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a final state-ek val�k.
	 * @param template Az UPPAAL template, amelybe a location�ket kell rakni
	 * @throws IncQueryException
	 */
	private void createLocationsFromExitNodes(Region region, Template template) throws IncQueryException {
		// A k�l�nb�z� final state-ek megk�l�nb�ztet�s�re
		int id = 0; 
		// Lek�rj�k a final state-eket
		Collection<ExitNodesMatch> allExitNodes = matcher.getAllExitNodes();
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket		
		for (ExitNodesMatch exitNodesMatch : allExitNodes) {						
			if (exitNodesMatch.getRegion() == region) {
				// L�trehozunk egy �j locationt
				Location exitNode = builder.createLocation("ExitNode" + (id++), template);
				stateLocationMap.put(exitNodesMatch.getExit(), exitNode); // Az exit node-location p�rokat betessz�k a map-be	
				builder.setLocationComment(exitNode, "An exit node");
			}
		}
	}
	
	/**
	 * Ez a met�dus felel�s az exit node-okba vezet� �lek broadcast ! szinkroniz�ci�j�nak, �s a felette l�v� r�gi�k ? szinkorniz�ci�j�nak l�trehoz�s��rt.
	 * @throws IncQueryException
	 */
	private void createUpdatesForExitNodes() throws IncQueryException {
		// Lek�rj�k az exit node-okat
		Collection<ExitNodesMatch> allExitNodes = matcher.getAllExitNodes();
		for (ExitNodesMatch exitNodesMatch : allExitNodes) {
			// Be�ll�tjuk, hogy az �sszes �tmenet elveszi az �sszes felette l�v� region valids�g�t
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");	
			for (SourceAndTargetOfTransitionsMatch transitionMatch : matcher.getAllTransitions()) {
				if (transitionMatch.getTarget() == exitNodesMatch.getExit()) {
					// Ez csak nem composite state-ekre megy, hiszen egy �len csak egy szinkroniz�ci� lehet (�s composite eset�n a kimen�nek van m�r)
					if (!Helper.isCompositeState(transitionMatch.getSource())) {	
						builder.setEdgeSync(transitionEdgeMap.get(transitionMatch.getTransition()), syncChanVar + (syncChanId), true);
						builder.setEdgeUpdate(transitionEdgeMap.get(transitionMatch.getTransition()), isActiveVar + " = false");
						// Guardot nem �ll�tunk, azt majd a k�z�s met�dusban
						builder.setEdgeComment(transitionEdgeMap.get(transitionMatch.getTransition()), "Exit node-ba vezeto el, kilep a templatebol.");
					}
					// Ha composite state, l�trehozunk egy exit locationt
					else {
						Location exitLoc = builder.createLocation("CompositeStateExit" + (exitStateId++), regionTemplateMap.get(transitionMatch.getSource().getParentRegion()));
						builder.setLocationCommitted(exitLoc);
						Edge exitEdge = builder.createEdge(regionTemplateMap.get(transitionMatch.getSource().getParentRegion()));
						builder.setEdgeSource(exitEdge, exitLoc);
						builder.setEdgeTarget(exitEdge, stateLocationMap.get(exitNodesMatch.getExit()));
						builder.setEdgeSync(exitEdge, syncChanVar + (syncChanId), true);
						builder.setEdgeUpdate(exitEdge, isActiveVar + " = false");
						// Guardot nem �ll�tunk, azt majd a k�z�s met�dusban
						builder.setEdgeComment(exitEdge, "Exit node-ba vezeto el, kilep a templatebol.");
						// Bej�v� �l targetj�t �t�ll�tjuk az exitLoc-ra
						builder.setEdgeTarget(transitionEdgeMap.get(transitionMatch.getTransition()), exitLoc);
						// Composite state-et betessz�k a mapbe
						hasExitLoc.put(transitionMatch.getSource(), exitEdge);
					}
				}
			}
			// Letiltjuk az �sszes felette l�v� r�gi�t, ehhez lek�rj�k a felette l�v� region�ket
			List<Region> regionList = new ArrayList<Region>();
			regionList = Helper.getThisAndUpperRegions(regionList, exitNodesMatch.getRegion());
			// Kivessz�k a saj�t region�t
			regionList.remove(exitNodesMatch.getRegion());
			// Letiltjuk a r�gi�kat
			setAllRegionsWithSync(false, false, regionList);
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL edge-eket az azonos regionbeli Yakindu transition-�k alapj�n az EdgesInSameRegionMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a transition�k val�k.
	 * @param template Az UPPAAL template, amelybe az edgeket kell rakni.
	 * @throws IncQueryException
	 */
	private void createEdges(Region region, Template template) throws IncQueryException {
		//Lek�rj�k a transition match-eket		
		Collection<EdgesInSameRegionMatch> edgesInSameRegionMatches = matcher.getAllEdgesInSameRegion();	
		// Megn�zz�k a transition match-eket �s l�trehozzuk az edge-eket a megfelel� guardokkal �s effectekkel
		for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatches) {											
			// Ha a k�t v�gpont a helyes region-ben van
			if (edgesInSameRegionMatch.getParentRegion() == region &&
					stateLocationMap.containsKey(edgesInSameRegionMatch.getSource()) && stateLocationMap.containsKey(edgesInSameRegionMatch.getTarget())) {								
				//L�trehozunk egy edge-t
				Edge anEdge = builder.createEdge(template); 
				//Be�ll�tjuk az edge forr�s�t �s c�lj�t
				anEdge.setSource(stateLocationMap.get(edgesInSameRegionMatch.getSource()));
				anEdge.setTarget(stateLocationMap.get(edgesInSameRegionMatch.getTarget()));
				transitionEdgeMap.put(edgesInSameRegionMatch.getTransition(), anEdge);
			}				
		}
	}
	
	/**
	 * Ez a met�dus hozza l�tre a composite state-ek entry locationj�t. Ez a state-be l�p�skor az alr�gi�kba val� l�p�s miatt sz�ks�ges.
	 * (Hogy az minden esetben megval�suljon.)
	 * @throws IncQueryException
	 */
	private void createEntryForCompositeStates() throws IncQueryException {
		// Lek�rj�k az �llapotokat
		Collection<CompositeStatesMatch> allCompositeStatesMatches = matcher.getAllCompositeStates();
		// Megn�zz�k a state matcheket �s l�trehozzuk az entry location�ket
		for (CompositeStatesMatch compositeStateMatch : allCompositeStatesMatches) {				
			// L�trehozzuk a locationt, majd a megfelel� �leket a megfelel� location-�kbe k�tj�k
			Location stateEntryLocation = createEntryLocation(compositeStateMatch.getCompositeState(), compositeStateMatch.getParentRegion());
			// �t�ll�tjuk a bej�v� �lek targetj�t, ehhez felhaszn�ljuk az �sszes �let lek�rdez� met�dust
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
				if (sourceAndTargetOfTransitionsMatch.getTarget() == compositeStateMatch.getCompositeState()) {
					transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()).setTarget(stateEntryLocation);
				}
			}
		}
	}
	
	/**
	 * Ez a met�dus l�trehoz egy (committed) entry locationt egy state-nek.
	 * @param vertex A Yakindu vertex, amelynek entry locationt szeretn�nk l�trehozni.
	 * @param region Yakindu region, a state parentRegionje.
	 * @return Uppaal location, amely a megadott vertex entry locationek�nt funkcion�l.
	 */
	private Location createEntryLocation(Vertex vertex, Region region) {
		// L�trehozzuk a locationt, majd a megfelel� �leket a megfelel� location-�kbe k�tj�k
		Location stateEntryLocation = builder.createLocation("EntryLocationOf" + vertex.getName() + (entryStateId++), regionTemplateMap.get(region));
		builder.setLocationCommitted(stateEntryLocation);
		Edge entryEdge = builder.createEdge(regionTemplateMap.get(region));
		builder.setEdgeSource(entryEdge, stateEntryLocation);
		builder.setEdgeTarget(entryEdge, stateLocationMap.get(vertex));
		// Berakjuk a state-edge p�rt a map-be
		hasEntryLoc.put(vertex, entryEdge);
		return stateEntryLocation;
	}
	
	/**
	 * Ez a met�dus hozza l�tre a composite state-ekbe vezet� �len a broadcast ! szinkorniz�ci�s csatorn�t, 
	 * �s az eggyel alatta l�v� r�gi�kban a ? szinkorniz�ci�s csatorn�kat. Ez ut�bbi v�gpontja att�l f�gg, hogy �rtelmezett-e a r�gi�ban history.
	 * @throws IncQueryException
	 */
	private void createEntryEdgesForAbstractionLevels() throws IncQueryException {
		// Lek�rj�k a composite �llapotokat
		Collection<CompositeStatesMatch> allCompositeStatesMatches = matcher.getAllCompositeStates();
		// Megn�zz�k a state matcheket �s l�trehozzuk az entry location�ket
		for (CompositeStatesMatch compositeStateMatch : allCompositeStatesMatches) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge entryEdge = hasEntryLoc.get(compositeStateMatch.getCompositeState());
			builder.setEdgeSync(entryEdge, syncChanVar + (syncChanId), true);
			// Minden eggyel alatti r�gi�ban l�trehozzuk a sz�ks�ges ? sync-eket
			setAllRegionsWithSync(true, true, compositeStateMatch.getCompositeState().getRegions());
		}
	}
	
	/**
	 * Ez a met�dus hozza l�tre a composite state-ekb�l kivezet� �leken a broadcast ! szinkroniz�ci�s csatorn�t,
	 * �s minden alatta l�v� r�gi�ban a ? szinkroniz�ci�s csatorn�kat. Ut�bbi esetben a csatorna mindig �nmag�ba vezet.
	 * @throws IncQueryException
	 */
	private void createExitEdgesForAbstractionLevels() throws IncQueryException {
		// Lek�rj�k a composite �llapotokat
		Collection<CompositeStatesMatch> allCompositeStateMatches = matcher.getAllCompositeStates();
		// Megn�zz�k az �sszes compositeState matchet
		for (CompositeStatesMatch compositeStateMatch : allCompositeStateMatches) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			// Minden kimen� �lre r��rjuk a kil�p�si sync-et
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
				if (sourceAndTargetOfTransitionsMatch.getSource() == compositeStateMatch.getCompositeState()) {
					builder.setEdgeSync(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), syncChanVar + (syncChanId), true);
				}
			}
			// Letiltjuk az �sszes alatta l�v� region-t
			List<Region> subregionList = new ArrayList<Region>();
			Helper.addAllSubregionsToRegionList(compositeStateMatch.getCompositeState(), subregionList);
			setAllRegionsWithSync(false, false, subregionList);			
		}
	}
	
	/**
	 * Minden megadott r�gi�ban l�trehozza a ? szinkroniz�ci�s csatorn�kat, �s azokon az �rv�nyess�gi v�ltoz�k updatejeit.
	 * Ezek a csatorn�k vagy �nmagukba vagy az region entrybe vezetnek.
	 * @param toBeTrue Enged�lyezni vagy tiltani szeretn�nk-e a r�gi�kat.
	 * @param regionList Yakindu region�k list�ja, amelyeken l�tre szeretn�nk hozni a ? csatorn�kat az update-ekkel.
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
				// Az adott subregion vertexeit vizsg�ljuk
				if (verticesOfRegionMatch.getRegion() == subregion) {
					// Choice-okb�l nem csin�lunk magukba �leket, azokban elvileg nem tart�zkodhatunk
					if (!(Helper.isChoice(verticesOfRegionMatch.getVertex())) && !(Helper.isEntry(verticesOfRegionMatch.getVertex()))) {
						Edge syncEdge = builder.createEdge(regionTemplateMap.get(subregion));
						builder.setEdgeSync(syncEdge, syncChanVar + syncChanId, false);
						builder.setEdgeUpdate(syncEdge, isActiveVar + " = " + ((toBeTrue) ? "true" : "false"));
						builder.setEdgeSource(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
						// Ha bel�p�sre enged�lyezz�k a r�gi�t, akkor vizsg�lni kell, hogy hova k�ss�k a szinkorniz�ci�s �l v�gpontj�t
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
						// Kil�p�skor nem vizsg�lhatjuk, hogy van-e history pl.: entryLoc-ja van valamelyik composite state-nek �s az committed
						else {
							builder.setEdgeTarget(syncEdge, stateLocationMap.get(verticesOfRegionMatch.getVertex()));
						}
					}
				}
			}
		}
	}
	
	/**
	 * L�trehozza azokat az �leket, amelyeknek v�gpontjai nem ugyanazon regionben tal�lhat�k.
	 * @throws IncQueryException
	 */
	private void createEdgesForDifferentAbstraction() throws IncQueryException {
		//Lek�rj�k a transition match-eket		
		Collection<EdgesAcrossRegionsMatch> allAcrossTransitionMatches = matcher.getAllEdgesAcrossRegions();	
		// Megn�zz�k a transition match-eket �s l�trehozzuk az edge-eket a megfelel� guardokkal �s effectekkel
		for (EdgesAcrossRegionsMatch acrossTransitionMatch : allAcrossTransitionMatches) {											
			// Ha a k�t v�gpont nem azonos region-ben van:
			// Megn�zz�k melyik milyen szint�, �s aszerint hozzuk l�tre a szinkroniz�ci�s csatorn�kat
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
	 * L�trehozza az absztrakci�s szintek k�z�tti tranzici�khoz sz�ks�ges �leket.
	 * Csak akkor m�k�dik, ha a source szintje kisebb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzici� kezd�pontja.
	 * @param target Yakindu vertex, a tranzici� v�gpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfels� szinten l�trehozott edget.
	 * @param lastLevel Eg�sz sz�m, amely megmondja, hogy a target h�nyadik szinten van.
	 * @throws IncQueryException 
	 */ 
	private void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions) throws IncQueryException {
		// Rekurzi�:
		// Visszamegy�nk a legf�ls� szintre, majd onnan visszal�pkedve, sorban minden szinten l�trehozzuk a szinkroniz�ci�kat
		if (source.getParentRegion() != target.getParentRegion()) {
			visitedRegions.add(target.getParentRegion());
			createEdgesWhenSourceLesser(source, (Vertex) target.getParentRegion().getComposite(), transition, lastLevel, levelDifference, visitedRegions);
		}
		// Ha a legf�ls� szintet el�rt�k:
		// L�trehozunk �j sync v�ltoz�t, �s a source-b�l a composite statebe vezet�nk egy �let a sync v�ltoz�val
		if (source.getParentRegion() == target.getParentRegion()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge abstractionEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(abstractionEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(abstractionEdge, stateLocationMap.get(target));
			builder.setEdgeSync(abstractionEdge, syncChanVar + (syncChanId), true);
			builder.setEdgeComment(abstractionEdge, "A Yakinduban alacsonyabb absztrakcios szinten levo vertexbe vezeto el.");
			// Ha a targetnek van entryEventje, akkor azt r� kell �rni az �lre
			if (Helper.hasEntryEvent(target)) {
				for (StatesWithEntryEventMatch statesWithEntryEventMatch : matcher.getAllStatesWithEntryEvent()) {
					if (statesWithEntryEventMatch.getState() == target) {
						String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
						builder.setEdgeUpdate(abstractionEdge, effect);
					}
				}
			}
			// Ez az �l felel majd meg a region�k�n �t�vel� transitionnek
			transitionEdgeMap.put(transition, abstractionEdge);
			// Ha a target composite state, akkor bel�p�sre minden alr�gi�j�ba is bel�p�nk
			if (Helper.isCompositeState(target)) {
				List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions()); // Tal�n addAll k�ne?
				pickedSubregions.removeAll(visitedRegions);
				setAllRegionsWithSync(true, false, pickedSubregions);				
			}
		}
		// Ha nem a legf�ls� szinten vagyunk, akkor l�trehozzuk a ? szinkroniz�ci�s �leket minden �llapotb�l a megfelel� �llapotba
		else {
			for (VerticesOfRegionsMatch verticesOfRegionsMatch : matcher.getAllVerticesOfRegions()) {
				if (verticesOfRegionsMatch.getRegion() == target.getParentRegion()) {
					Edge syncEdge = builder.createEdge(regionTemplateMap.get(verticesOfRegionsMatch.getRegion()));
					builder.setEdgeSource(syncEdge, stateLocationMap.get(verticesOfRegionsMatch.getVertex()));
					builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));	
					// Ha a targetnek van entryEventje, akkor azt r� kell �rni az �lre
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
			// Altemplate "initial location"-j�t is bek�tj�k a megfelel� locationbe
			if (hasInitLoc.containsKey(regionTemplateMap.get(target.getParentRegion()))) {
				Edge syncEdge = builder.createEdge(regionTemplateMap.get(target.getParentRegion()));
				builder.setEdgeSource(syncEdge, hasInitLoc.get(regionTemplateMap.get(target.getParentRegion())));
				builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));	
				builder.setEdgeSync(syncEdge, syncChanVar + (syncChanId), false);
				builder.setEdgeUpdate(syncEdge, isActiveVar + " = true");	
			}
			// Ha a target composite state, akkor ezt minden region-j�re megism�telj�k, kiv�ve ezt a regiont
			if (Helper.isCompositeState(target)) {
				List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions()); // Tal�n addAll k�ne?
				pickedSubregions.removeAll(visitedRegions);
				setAllRegionsWithSync(true, false, pickedSubregions);				
			}
		}		
	}
	
	/**
	 * L�trehozza az absztrakci�s szintek k�z�tti tranzici�khoz sz�ks�ges �leket.
	 * Csak akkor m�k�dik, ha a source szintje nagyobb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzici� kezd�pontja.
	 * @param target Yakindu vertex, a tranzici� v�gpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfels� szinten l�trehozott edget.
	 * @param lastLevel Eg�sz sz�m, amely megmondja, hogy a source h�nyadik szinten van.
	 * @throws IncQueryException 
	 */
	private void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel, List<Region> visitedRegions) throws IncQueryException {
		// A legals� szinten l�trehozunk egy mag�ba vezet� �let:  
		// Ez felel meg az alacsonyabb szintr�l magasabb szinten l�v� vertexbe vezet� �tmenetnek
		if (Helper.getLevelOfVertex(source) == lastLevel) {
			visitedRegions.add(source.getParentRegion());
			// L�trehozunk egy szinkroniz�ci�s csatorn�t r�
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), true);
			// Letiltjuk ezt a r�gi�t, mert �nmag�ra nem tud szinkroniz�lni
			builder.setEdgeUpdate(ownSyncEdge, isActiveVar + " = false");
			builder.setEdgeComment(ownSyncEdge, "A Yakinduban magasabb absztrakcios szinten levo vertexbe vezeto el.");
			// Ez az �l felel majd meg a region�k�n �t�vel� transitionnek
			transitionEdgeMap.put(transition, ownSyncEdge);
			createEdgesWhenSourceGreater((Vertex) source.getParentRegion().getComposite(), target, transition, lastLevel, visitedRegions);
		}
		// A fels� szint
		else if (Helper.getLevelOfVertex(source) == Helper.getLevelOfVertex(target)) {
			// A fels� szinten l�trehozzuk az �let, amely fogadja a szinkroniz�ci�t
			Edge ownSyncEdge = builder.createEdge(regionTemplateMap.get(source.getParentRegion()));
			builder.setEdgeSource(ownSyncEdge, stateLocationMap.get(source));
			builder.setEdgeTarget(ownSyncEdge, stateLocationMap.get(target));
			builder.setEdgeSync(ownSyncEdge, syncChanVar + (syncChanId), false);
			// Exit eventet r�rakjuk, ha van
			if (Helper.hasExitEvent(source)) {
				for (StatesWithExitEventWithoutOutgoingTransitionMatch statesWithExitEventMatch : matcher.getAllStatesWithExitEventWithoutOutgoing()) {
					if (statesWithExitEventMatch.getState() == source) {
						String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());
						builder.setEdgeUpdate(ownSyncEdge, effect);						
					}
				}
			}
			// Itt letiltjuk az �sszes source alatt l�v� r�gi�t, jelezve, hogy azok m�r nem �rv�nyesek
			// Kiv�ve a megl�togatottakat
			List<Region> subregionList = new ArrayList<Region>();
			State sourceState = (State) source;
			Helper.addAllSubregionsToRegionList(sourceState, subregionList);
			subregionList.removeAll(visitedRegions);
			setAllRegionsWithSync(false, false, subregionList);
			return;
		}
		// K�zb�ls� szinteken csak k�zzel l�trehozzuk a sync �leket, letiltjuk a r�gi�t, �s r�juk �rjuk az exit expressiont, ha van
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
	 * Ez a met�dus l�trehozza az UPPAAL edge-eken az update-eket a Yakindu effectek alapj�n.
	 * @throws Exception 
	 */
	private void setEdgeUpdates() throws Exception {
		// V�gigmegy�nk minden transition-�n, amelynek van effectje
		for (EdgesWithEffectMatch edgesWithEffectMatch : matcher.getAllEdgesWithEffect()) {
			String effect = UppaalCodeGenerator.transformExpression(edgesWithEffectMatch.getExpression());
			builder.setEdgeUpdate(transitionEdgeMap.get(edgesWithEffectMatch.getTransition()), effect);
		}
		// Megcsi�ljuk a state update-eket is
		setEdgeUpdatesFromStates();
		// Itt csin�ljuk meg a raise eventeket
		createRaisingEventSyncs();
	}
	
	/**
	 * Ez a met�dus felel az egyes state-ek entry/exit triggerjeinek hat�sai�rt.
	 * Minden Entry triggerrel rendelkez� state eset�n l�trehoz egy committed location-t,
	 * amelyb�l a kivezet� �l a megfelel� locationba vezet, �s tartalmazza a sz�ks�ges update-eket.
	 * Minden Exit triggerrel rendelkez� state eset�n a kimen� �lekre rakja r� a sz�ks�ges update-eket.
	 * K�nnyen bel�that�, hogy Exit eset�n nem m�k�dne az Entry-s megold�s.
	 * @throws IncQueryException 
	 */
	private void setEdgeUpdatesFromStates() throws IncQueryException {
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : matcher.getAllStatesWithEntryEvent()) {
			// Transzform�ljuk a kifejez�st
			String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
			// Ha nincs m�g entry state-je
			if (!hasEntryLoc.containsKey(statesWithEntryEventMatch.getState())) {
				// L�trehozzuk a locationt, majd a megfelel� �leket a megfelel� location-�kbe k�tj�k
				Location stateEntryLocation = createEntryLocation(statesWithEntryEventMatch.getState(), statesWithEntryEventMatch.getParentRegion());
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
				// �t�ll�tjuk a bej�v� �lek targetj�t
				for (EdgesInSameRegionMatch edgesInSameRegionMatch : matcher.getAllEdgesInSameRegion()) {
					if (edgesInSameRegionMatch.getTarget() == statesWithEntryEventMatch.getState()) {
						transitionEdgeMap.get(edgesInSameRegionMatch.getTransition()).setTarget(stateEntryLocation);
					}
				}
			}
			// Ha m�r van entry state-je
			else {
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
			}
		}
		// Ha Exit, akkor r��rjuk az update-et minden kimen� �lre
		// Nem haszn�lhatunk committed locationt
		for (StatesWithExitEventMatch statesWithExitEventMatch : matcher.getAllStatesWithExitEvent()) {
			String effect = UppaalCodeGenerator.transformExpression(statesWithExitEventMatch.getExpression());			
			builder.setEdgeUpdate(transitionEdgeMap.get(statesWithExitEventMatch.getTransition()), effect);			
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL edge-eken az guardokat a Yakindu guardok alapj�n.
	 * @throws IncQueryException 
	 */
	private void setEdgeGuards() throws IncQueryException {
		// V�gigmegy�nk minden transition-�n
		for (EdgesWithGuardMatch edgesWithGuardMatch : matcher.getAllEdgesWithGuard()) {
			// Ha van guard-ja, akkor azt transzform�ljuk, �s r��rjuk az edge-re
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
	 * L�trehozza a template-ek �rv�nyes m�k�d�s�hez sz�ks�ges guardokat. (isValid)
	 * @param transition A Yakindu transition, amelynek a megfeleltetett UPPAAL �l�re rakjuk r� az �rv�nyess�gi guardot.
	 * @throws IncQueryException 
	 */
	private void createTemplateValidityGuards() throws IncQueryException {
		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : matcher.getAllTransitions()) {
			// R�tessz�k a guardokra a template �rv�nyess�gi v�toz�t is
			if (builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != null && builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())) != "") {
				builder.setEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), isActiveVar + " && " + builder.getEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition())));
			} 
			else {
				builder.setEdgeGuard(transitionEdgeMap.get(sourceAndTargetOfTransitionsMatch.getTransition()), isActiveVar);
			}		
		}
	}
	
	/**
	 * Ez a met�dus felel az event raising megval�s�t�s��rt.
	 * @throws Exception Jelzi, ha nem m�k�dik a szinkroniz�ci�. (Nem t�k�letes m�g ez a k�d.)
	 */
	private void createRaisingEventSyncs() throws Exception {
		for (EdgesWithRaisingEventMatch edgesWithRaisingEventMatch : matcher.getAllEdgesWithRaisingEvent()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			if (transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()).getSynchronization() != null) {
				throw new Exception("Baj van a raising cuccal, mert m�r van syncje.");
			}
			builder.setEdgeSync(transitionEdgeMap.get(edgesWithRaisingEventMatch.getTransition()), syncChanVar + (syncChanId), true);
			for (EdgesWithTriggerElementReferenceMatch edgesWithTriggerElementReferenceMatch : matcher.getAllEdgesWithTriggerElementReference()) {
				if (edgesWithTriggerElementReferenceMatch.getElement() == edgesWithRaisingEventMatch.getElement()) {
					if (transitionEdgeMap.get(edgesWithTriggerElementReferenceMatch.getTransition()).getSynchronization() != null) {
						throw new Exception("Baj van a raising cuccal, mert m�r van syncje.");
					}
					else {
						builder.setEdgeSync(transitionEdgeMap.get(edgesWithTriggerElementReferenceMatch.getTransition()), syncChanVar + (syncChanId), false);
					}
				}
			}
		}
	}
	
	/**
	 * Ez a met�dus hozza l�tre a parallel region�kben az entry node-b�l val� kil�p�shez sz�ks�ges szinkroniz�ci�kat.
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
	 * Ez a met�dus hozza l�tre az egyes Yakundu kimeneti �leken szerepl� id�f�gg� viselked�snek megfelel� (after 1 s) Uppaal clock v�ltoz�k manipul�l�s�t.
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
	 * Ez a met�dus l�trehozza a triggereket, �s a hozz� sz�ks�ges �j location�ket �s edge-eket a megfelel� template-ekben. 
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
