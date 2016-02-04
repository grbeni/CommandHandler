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
 * Az oszt�ly, amely a Yakindu p�ld�nymodell alapj�n l�trehozza az UPPAAL p�ld�nymodellt.
 * F�gg a PatternMatcher �s az UppaalModelBuilder oszt�lyokt�l.
 * @author Graics Bence
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
	
	// IncQuery engines
	private IncQueryEngine engine;
	private RunOnceQueryEngine runOnceEngine;
	 
	// Uppaal v�ltoz�nevek
	private final String syncChanVar = "syncChan";
	private final String isActiveVar = "isActive";
	private final String clockVar = "Timer";
	private final String endVar = "end";
			
	// Az UPPAAL modell fel�p�t�sre
	private UppaalModelBuilder builder = null;	
			
	// Egy Map a Yakindu:Region -> UPPAAL:Template lek�pz�sre									 								
	private Map<Region, Template> regionTemplateMap = null;
			
	// Egy Map a Yakindu:Vertex -> UPPAAL:Location lek�pz�sre									 								
	private Map<Vertex, Location> stateLocationMap = null;
			
	// Egy Map a Yakindu:Transition -> UPPAAL:Edge lek�pz�sre
	private Map<Transition, Edge> transitionEdgeMap = null;
			
	// Egy Map a Yakindu:Vertex -> UPPAAL:Edge lek�pz�sre
	// Az entryLocation-nel rendelkez� vertex Uppaal megfelel� locationj�be vezet� �let adja vissza
	private Map<Vertex, Edge> hasEntryLoc = null;
	
	// Egy Map, amely t�rolja az egyes Vertexek triggerLocation kimen� �l�t
	private Map<Transition, Edge> hasTriggerPlusEdge = null;
	
	// Egy Map, amely t�rolja az altemplate-ek "initial location"-j�t
	private Map<Template, Location> hasInitLoc = null;
			
	// Szinkroniz�ci�s csatorn�k l�trehoz�s�ra
	private int syncChanId = 0;
	// EntryLoc n�v gener�l�sra
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
									// UPPAAL modell inicializ�ci�
									builder = UppaalModelBuilder.getInstance();
									builder.createNTA(statechart.getName());
									
									// Map-ek inicializ�l�sa
									regionTemplateMap = new HashMap<Region, Template>();									 								
									stateLocationMap = new HashMap<Vertex, Location>();
									transitionEdgeMap = new HashMap<Transition, Edge>();
									hasEntryLoc = new HashMap<Vertex, Edge>();
									hasTriggerPlusEdge = new HashMap<Transition, Edge>();
									hasInitLoc = new HashMap<Template, Location>();
									
									// ID v�ltoz�k resetel�se
									syncChanId = 0;
									entryStateId = 0;
									raiseId = 0;
									
									// Csak akkor szennyezz�k az Uppaal modellt end v�ltoz�val, ha van final state a Yakindu modellben
									if (Helper.hasFinalState()) {
										builder.addGlobalDeclaration("bool " + endVar + " = false;" );
									}
									
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
									// L�trehozza a q file-t
									UppaalQueryGenerator.saveToQ(filen);
									
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
	 * Ez a met�dus l�trehozza az egyes UPPAAL template-eket a Yakindu region�k alapj�n.
	 * @throws Exception 
	 */
	private void createTemplates() throws Exception {
		// Lek�rj�k a r�gi�kat
		EntryOfRegionsMatcher entryOfRegionsMatcher = engine.getMatcher(EntryOfRegionsQuerySpecification.instance());
		Collection<EntryOfRegionsMatch> regionMatches = entryOfRegionsMatcher.getAllMatches();
		// V�gigmegy�nk a r�gi�kon, �s l�trehozzuk a Yakindu modellnek megfeleltethet� elemeket.
		for (EntryOfRegionsMatch regionMatch : regionMatches) {
			Template template = builder.createTemplate(Helper.getTemplateNameFromRegionName(regionMatch.getRegion()));			
			// Kiszedj�k a template nevekb�l a sz�k�z�ket, mert az UPPAAL nem szereti
			if (Helper.isTopRegion(regionMatch.getRegion())) {
				// M�gis foglalkozunk, hogy a region�k�n �t�vel� tranzici�k helyes lefut�sa garant�lhat� legyen
				builder.addLocalDeclaration("bool " + isActiveVar + " = true;", template);
			} 
			else {
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
		
		// Final state bemen� edge-einek updateinek megad�sa
		createFinalStateEdgeUpdates();
		
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
		
		// Create events as broadcast channels
		createEvents();
		
		// Create loop edges + raising locations as local reactions
		createLocalReactions();
		
		// Template guardok berak�sa
		createTemplateValidityGuards();
		
		// Entry kimen� �lek beSyncel�se
		// Sajnos nem m�k�dik ebben a form�ban, hogy egyszerre j�jjenek ki az entry node-b�l :(
		//createSyncFromEntries();
		
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
		StatesMatcher statesMatcher = engine.getMatcher(StatesQuerySpecification.instance());
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket
		for (StatesMatch stateMatch : statesMatcher.getAllMatches(null, region, null)) {												
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
		ChoicesMatcher choicesMatcher = engine.getMatcher(ChoicesQuerySpecification.instance());
		// Megn�zz�k a choice matcheket �s l�trehozzuk a location-�ket		
		for (ChoicesMatch choiceMatch : choicesMatcher.getAllMatches(null, region)) {				
			Location aLocation = builder.createLocation("Choice" + id++, template);
			builder.setLocationCommitted(aLocation);
			stateLocationMap.put(choiceMatch.getChoice(), aLocation); // A choice-location p�rokat betessz�k a map-be	
			builder.setLocationComment(aLocation, "A choice");
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
		FinalStatesMatcher finalStatesMatcher = engine.getMatcher(FinalStatesQuerySpecification.instance());
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket		
		for (FinalStatesMatch finalStateMatch : finalStatesMatcher.getAllMatches(null, region)) {										
			Location aLocation = builder.createLocation("FinalState" + id++, template);
			stateLocationMap.put(finalStateMatch.getFinalState(), aLocation); // A final state-location p�rokat betessz�k a map-be	
			builder.setLocationComment(aLocation, "A final state");
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza a final state bemen� �l�n az end = false update-eket, ami letilt minden tranzici�t.
	 * @throws IncQueryException
	 */
	private void createFinalStateEdgeUpdates() throws IncQueryException {
		FinalStateEdgeMatcher finalStateEdgeMatcher = engine.getMatcher(FinalStateEdgeQuerySpecification.instance());
		for (FinalStateEdgeMatch finalStateEdgeMatch : finalStateEdgeMatcher.getAllMatches()) {
			builder.setEdgeUpdate(transitionEdgeMap.get(finalStateEdgeMatch.getIncomingEdge()), endVar + " = true");
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL location�ket a Yakindu exit node-ok alapj�n az ExitNodesMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a final state-ek val�k.
	 * @param template Az UPPAAL template, amelybe a location�ket kell rakni
	 * @throws IncQueryException
	 */
	private void createLocationsFromExitNodes(Region region, Template template) throws IncQueryException {
		// A k�l�nb�z� exit node-ok megk�l�nb�ztet�s�re
		int id = 0; 
		// Lek�rj�k a exit node-okat
		ExitNodesMatcher exitNodesMatcher = engine.getMatcher(ExitNodesQuerySpecification.instance());
		// Megn�zz�k a state matcheket �s l�trehozzuk a location-�ket		
		for (ExitNodesMatch exitNodesMatch : exitNodesMatcher.getAllMatches(null, region)) {
			// L�trehozunk egy �j locationt
			Location exitNode = builder.createLocation("ExitNode" + (id++), template);
			stateLocationMap.put(exitNodesMatch.getExit(), exitNode); // Az exit node-location p�rokat betessz�k a map-be	
			builder.setLocationComment(exitNode, "An exit node");
		}
	}
	
	/**
	 * Ez a met�dus felel�s az exit node-okba vezet� �lek broadcast ! szinkroniz�ci�j�nak, �s a felette l�v� r�gi�k ? szinkorniz�ci�j�nak l�trehoz�s��rt.
	 * @throws IncQueryException
	 */
	private void createUpdatesForExitNodes() throws IncQueryException {
		// Lek�rj�k az exit node-okat
		ExitNodeSyncMatcher exitNodeSyncMatcher = engine.getMatcher(ExitNodeSyncQuerySpecification.instance());
		for (ExitNodeSyncMatch exitNodesMatch : exitNodeSyncMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge exitNodeEdge = transitionEdgeMap.get(exitNodesMatch.getExitNodeTransition());
			builder.setEdgeSync(exitNodeEdge, syncChanVar + (syncChanId), true);
			builder.setEdgeSync(transitionEdgeMap.get(exitNodesMatch.getDefaultTransition()), syncChanVar + (syncChanId), false);
			//Nem kell letiltani az �ssezs alatta l�v� regiont, mert azt a kimen� �l automatikusan megcsin�lja			
		}
	}
	
	/**
	 * Ez a met�dus l�trehozza az UPPAAL edge-eket az azonos regionbeli Yakindu transition-�k alapj�n az EdgesInSameRegionMatcheket felhaszn�lva.
	 * @param region A Yakindu region, amelyb�l a transition�k val�k.
	 * @param template Az UPPAAL template, amelybe az edgeket kell rakni.
	 * @throws Exception 
	 */
	private void createEdges(Region region, Template template) throws Exception {
		//Lek�rj�k a transition match-eket	
		EdgesInSameRegionMatcher edgesInSameRegionMatcher = engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());
		// Megn�zz�k a transition match-eket �s l�trehozzuk az edge-eket a megfelel� guardokkal �s effectekkel
		for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, null, region)) {											
			// Ha a k�t v�gpont a helyes region-ben van
			if (!(stateLocationMap.containsKey(edgesInSameRegionMatch.getSource()) && stateLocationMap.containsKey(edgesInSameRegionMatch.getTarget()))) {								
				throw new Exception("The source or the target is null.");
			}
			//L�trehozunk egy edge-t
			Edge anEdge = builder.createEdge(template); 
			//Be�ll�tjuk az edge forr�s�t �s c�lj�t
			anEdge.setSource(stateLocationMap.get(edgesInSameRegionMatch.getSource()));
			anEdge.setTarget(stateLocationMap.get(edgesInSameRegionMatch.getTarget()));
			transitionEdgeMap.put(edgesInSameRegionMatch.getTransition(), anEdge);
							
		}
	}
	
	/**
	 * Ez a met�dus hozza l�tre a composite state-ek entry locationj�t. Ez a state-be l�p�skor az alr�gi�kba val� l�p�s miatt sz�ks�ges.
	 * (Hogy az minden esetben megval�suljon.)
	 * @throws Exception 
	 */
	private void createEntryForCompositeStates() throws Exception {
		// Lek�rj�k az �llapotokat
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		// Megn�zz�k a state matcheket �s l�trehozzuk az entry location�ket
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {				
			// L�trehozzuk a locationt, majd a megfelel� �leket a megfelel� location-�kbe k�tj�k
			Location stateEntryLocation = createEntryLocation(compositeStateMatch.getCompositeState(), compositeStateMatch.getParentRegion());
			// �t�ll�tjuk a bej�v� �lek targetj�t, ehhez felhaszn�ljuk az �sszes �let lek�rdez� met�dust
			for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches(null, null, compositeStateMatch.getCompositeState())) {
				if ((transitionEdgeMap.containsKey(sourceAndTargetOfTransitionsMatch.getTransition()))) {
					//throw new Exception("The transition is not mapped: " + sourceAndTargetOfTransitionsMatch.getTransition().getSource().getName() + " -> " + sourceAndTargetOfTransitionsMatch.getTransition().getTarget().getName());
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
	 * @throws Exception 
	 */
	private void createEntryEdgesForAbstractionLevels() throws Exception {
		// Lek�rj�k a composite �llapotokat
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		// Megn�zz�k a state matcheket �s l�trehozzuk az entry location�ket
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			Edge entryEdge = hasEntryLoc.get(compositeStateMatch.getCompositeState());
			builder.setEdgeSync(entryEdge, syncChanVar + (syncChanId), true);
			for (Region subregion : compositeStateMatch.getCompositeState().getRegions()) {
				generateNewInitLocation(subregion);
			}
			// Minden eggyel alatti r�gi�ban l�trehozzuk a sz�ks�ges ? sync-eket
			setAllRegionsWithSync(true, compositeStateMatch.getCompositeState().getRegions());			
		}
	}
	
	/**
	 * Ez a met�dus hozza l�tre a composite state-ekb�l kivezet� �leken a broadcast ! szinkroniz�ci�s csatorn�t,
	 * �s minden alatta l�v� r�gi�ban a ? szinkroniz�ci�s csatorn�kat. Ut�bbi esetben a csatorna mindig �nmag�ba vezet.
	 * @throws Exception 
	 */
	private void createExitEdgesForAbstractionLevels() throws Exception {
		int id = 0;
		// Lek�rj�k a composite �llapotokat
		CompositeStatesMatcher compositeStatesMatcher = engine.getMatcher(CompositeStatesQuerySpecification.instance());
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		// Megn�zz�k az �sszes compositeState matchet
		for (CompositeStatesMatch compositeStateMatch : compositeStatesMatcher.getAllMatches()) {
			builder.addGlobalDeclaration("broadcast chan " + syncChanVar + (++syncChanId) + ";");
			// Minden kimen� �lre r��rjuk a kil�p�si sync-et
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
			// Letiltjuk az �sszes alatta l�v� region-t
			List<Region> subregionList = new ArrayList<Region>();
			Helper.addAllSubregionsToRegionList(compositeStateMatch.getCompositeState(), subregionList);
			setAllRegionsWithSync(false, subregionList);			
		}
	}
	
	/**
	 * Minden megadott r�gi�ban l�trehozza a ? szinkroniz�ci�s csatorn�kat, �s azokon az �rv�nyess�gi v�ltoz�k updatejeit. Illetve egy initLocation-t, ha el�sz�r �p�tj�k fel a template-et.
	 * Ezek a csatorn�k vagy �nmagukba vagy az region entrybe vezetnek.
	 * @param needInit Kell-e initLocation a template-be.
	 * @param regionList Yakindu region�k list�ja, amelyeken l�tre szeretn�nk hozni a ? csatorn�kat az update-ekkel.
	 * @throws Exception 
	 */
	private void setAllRegionsWithSync(boolean toBeTrue, List<Region> regionList) throws Exception {
		VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
		for (Region subregion : regionList) {	
			for (VerticesOfRegionsMatch verticesOfRegionMatch : verticesOfRegionsMatcher.getAllMatches(subregion, null)) {
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
	 * L�trehozza azokat az �leket, amelyeknek v�gpontjai nem ugyanazon regionben tal�lhat�k.
	 * @throws Exception 
	 */
	private void createEdgesForDifferentAbstraction() throws Exception {
		//Lek�rj�k a transition match-eket		
		EdgesAcrossRegionsMatcher edgesAcrossRegionsMatcher = engine.getMatcher(EdgesAcrossRegionsQuerySpecification.instance());
		// Megn�zz�k a transition match-eket �s l�trehozzuk az edge-eket a megfelel� guardokkal �s effectekkel
		for (EdgesAcrossRegionsMatch acrossTransitionMatch : edgesAcrossRegionsMatcher.getAllMatches()) {											
			// Ha a k�t v�gpont nem azonos region-ben van:
			// Megn�zz�k melyik milyen szint�, �s aszerint hozzuk l�tre a szinkroniz�ci�s csatorn�kat
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
	 * L�trehozza az absztrakci�s szintek k�z�tti tranzici�khoz sz�ks�ges �leket.
	 * Csak akkor m�k�dik, ha a source szintje kisebb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzici� kezd�pontja.
	 * @param target Yakindu vertex, a tranzici� v�gpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfels� szinten l�trehozott edget.
	 * @param lastLevel Eg�sz sz�m, amely megmondja, hogy a target h�nyadik szinten van.
	 * @throws Exception 
	 */ 
	private void createEdgesWhenSourceLesser(Vertex source, Vertex target, Transition transition, int lastLevel, int levelDifference, List<Region> visitedRegions) throws Exception {
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
			setHelperEdgeEntryEvent(abstractionEdge, target, lastLevel);
			// Ez az �l felel majd meg a region�k�n �t�vel� transitionnek
			transitionEdgeMap.put(transition, abstractionEdge);
			// A target composite state, bel�p�sre minden alr�gi�j�ba is bel�p�nk
			setEdgeEntryAllSubregions(target, visitedRegions);
		}	
		// Ha nem a legf�ls� szinten vagyunk, akkor l�trehozzuk a ? szinkroniz�ci�s �leket minden �llapotb�l a megfelel� �llapotba
		else {
			VerticesOfRegionsMatcher verticesOfRegionsMatcher = engine.getMatcher(VerticesOfRegionsQuerySpecification.instance());
			for (VerticesOfRegionsMatch verticesOfRegionsMatch : verticesOfRegionsMatcher.getAllMatches(target.getParentRegion(), null)) {				
				setHelperEdges(stateLocationMap.get(verticesOfRegionsMatch.getVertex()), target, lastLevel);
			}
			// Altemplate "initial location"-j�t is bek�tj�k a megfelel� locationbe
			if (hasInitLoc.containsKey(regionTemplateMap.get(target.getParentRegion()))) {
				setHelperEdges(hasInitLoc.get(regionTemplateMap.get(target.getParentRegion())), target, lastLevel);				
			}
			// Ha a target composite state, akkor ezt minden region-j�re megism�telj�k, kiv�ve ezt a regiont
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
		// Ha utols� szinten vagyunk, �s egy composite state-be megy�nk, akkor az entryLocj�ba kell k�tni
		if (lastLevel == Helper.getLevelOfVertex(target) && hasEntryLoc.containsKey(target)) {
			builder.setEdgeTarget(syncEdge, builder.getEdgeSource(hasEntryLoc.get(target)));
		}							
		// Itt m�r nem kell entryLocba k�tni, mert az lehet, hogy elrontan� az als�bb r�gi�k helyes �llapotatit (teh�t csak legals� szinten kell entryLocba k�tni)
		else {					
			builder.setEdgeTarget(syncEdge, stateLocationMap.get(target));
		}					
		// Ha a targetnek van entryEventje, akkor azt r� kell �rni az �lre
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
		List<Region> pickedSubregions = new ArrayList<Region>(((State) target).getRegions()); // Tal�n addAll k�ne?
		pickedSubregions.removeAll(visitedRegions);
		setAllRegionsWithSync(true, pickedSubregions);		
		setSyncFromGeneratedInit(pickedSubregions);
	}
	
	/**
	 * L�trehozza az absztrakci�s szintek k�z�tti tranzici�khoz sz�ks�ges �leket.
	 * Csak akkor m�k�dik, ha a source szintje nagyobb, mint a target szintje.
	 * @param source Yakindu vertex, a tranzici� kezd�pontja.
	 * @param target Yakindu vertex, a tranzici� v�gpontja.
	 * @param transition Yakindu transition, ennek fogjuk megfeleltetni a legfels� szinten l�trehozott edget.
	 * @param lastLevel Eg�sz sz�m, amely megmondja, hogy a source h�nyadik szinten van.
	 * @throws Exception 
	 */
	private void createEdgesWhenSourceGreater(Vertex source, Vertex target, Transition transition, int lastLevel, List<Region> visitedRegions) throws Exception {
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
			setHelperEdgeExitEvent(ownSyncEdge, source, lastLevel);
			// Itt letiltjuk az �sszes source alatt l�v� r�gi�t, jelezve, hogy azok m�r nem �rv�nyesek
			// Kiv�ve a megl�togatottakat
			setEdgeExitAllSubregions(source, visitedRegions);
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
	 * Ez a met�dus l�trehozza az UPPAAL edge-eken az update-eket a Yakindu effectek alapj�n.
	 * @throws Exception 
	 */
	private void setEdgeUpdates() throws Exception {
		// V�gigmegy�nk minden transition-�n, amelynek van effectje
		EdgesWithEffectMatcher edgesWithEffectMatcher = engine.getMatcher(EdgesWithEffectQuerySpecification.instance());
		for (EdgesWithEffectMatch edgesWithEffectMatch : edgesWithEffectMatcher.getAllMatches()) {
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
		EdgesInSameRegionMatcher edgesInSameRegionMatcher = engine.getMatcher(EdgesInSameRegionQuerySpecification.instance());
		for (StatesWithEntryEventMatch statesWithEntryEventMatch : runOnceEngine.getAllMatches(StatesWithEntryEventMatcher.querySpecification())) {
			// Transzform�ljuk a kifejez�st
			String effect = UppaalCodeGenerator.transformExpression(statesWithEntryEventMatch.getExpression());
			// Ha nincs m�g entry state-je
			if (!hasEntryLoc.containsKey(statesWithEntryEventMatch.getState())) {
				// L�trehozzuk a locationt, majd a megfelel� �leket a megfelel� location-�kbe k�tj�k
				Location stateEntryLocation = createEntryLocation(statesWithEntryEventMatch.getState(), statesWithEntryEventMatch.getParentRegion());
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
				// �t�ll�tjuk a bej�v� �lek targetj�t
				for (EdgesInSameRegionMatch edgesInSameRegionMatch : edgesInSameRegionMatcher.getAllMatches(null, null, statesWithEntryEventMatch.getState(), null)) {				
					builder.setEdgeTarget(transitionEdgeMap.get(edgesInSameRegionMatch.getTransition()), stateEntryLocation);				
				}
			}
			// Ha m�r van entry state-je
			else {
				builder.setEdgeUpdate(hasEntryLoc.get(statesWithEntryEventMatch.getState()), effect);
			}
		}
		// Ha Exit, akkor r��rjuk az update-et minden kimen� �lre
		// Nem haszn�lhatunk committed locationt
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
	 * Ez a met�dus l�trehozza az UPPAAL edge-eken az guardokat a Yakindu guardok alapj�n.
	 * @throws IncQueryException 
	 */
	private void setEdgeGuards() throws IncQueryException {
		// V�gigmegy�nk minden transition-�n
		EdgesWithGuardMatcher edgesWithGuardMatcher = engine.getMatcher(EdgesWithGuardQuerySpecification.instance());
		EventsWithTypeMatcher eventsWithTypeMatcher = engine.getMatcher(EventsWithTypeQuerySpecification.instance());
		for (EdgesWithGuardMatch edgesWithGuardMatch : edgesWithGuardMatcher.getAllMatches()) {
			// Ha van guard-ja, akkor azt transzform�ljuk, �s r��rjuk az edge-re
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
	 * L�trehozza a template-ek �rv�nyes m�k�d�s�hez sz�ks�ges guardokat. (isActive)
	 * @param transition A Yakindu transition, amelynek a megfeleltetett UPPAAL �l�re rakjuk r� az �rv�nyess�gi guardot.
	 * @throws IncQueryException 
	 */
	private void createTemplateValidityGuards() throws IncQueryException {
		SourceAndTargetOfTransitionsMatcher sourceAndTargetOfTransitionsMatcher = engine.getMatcher(SourceAndTargetOfTransitionsQuerySpecification.instance());
		for (SourceAndTargetOfTransitionsMatch sourceAndTargetOfTransitionsMatch : sourceAndTargetOfTransitionsMatcher.getAllMatches()) {
			// R�tessz�k a guardokra a template �rv�nyess�gi v�toz�t is
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
	 * @throws Exception jelzi, ha nem m�k�dik a szinkroniz�ci�. 
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
	 * Ez a met�dus hozza l�tre a parallel region�kben az entry node-b�l val� kil�p�shez sz�ks�ges szinkroniz�ci�kat.
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
	 * Ez a met�dus hozza l�tre az egyes Yakundu kimeneti �leken szerepl� id�f�gg� viselked�snek megfelel� (after 1 s) Uppaal clock v�ltoz�k manipul�l�s�t.
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
	 * Ez a met�dus l�trehoz egy szinkroniz�ci�s �let egy �j location-b�l �s azt belek�ti a targetbe, r��rva a megadott szinkorniz�ci�t.
	 * @param target A location, ahova k�tni szeretn�nk az �let.
	 * @param locationName A n�v, amelyet adni szeretn�nk a l�trehozott locationnek.
	 * @param sync A szinkroniz�ci�, amelyet r� szeretn�nk tenni az �lre.
	 * @param template A template, amelybe bele szeretn�nk rakni a l�trehozott locationt.
	 * @return A szinkroniz�ci� edge, amely a l�trehozott locationb�l belevezet a target locationbe.
	 * @throws Exception Ezt akkor dobja, ha az �tadott szinkorniz�ci� ? szinkorniz�ci�. Ekkor a l�trehozott strukt�ra nem m�k�dhet j�l.
	 */
	private Edge createSyncLocation(Location target, String locationName, Synchronization sync) throws Exception {
		if (sync != null && sync.getKind().getValue() == 0) {
			throw new Exception("Egy ? sync-et akar �thelyezni!");
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
	 * Ez a met�dus l�trehoz egy szinkroniz�ci�s �let egy �j location-b�l �s azt belek�ti a targetbe, r��rva a megadott szinkorniz�ci�t.
	 * @param target A location, ahova k�tni szeretn�nk az �let.
	 * @param locationName A n�v, amelyet adni szeretn�nk a l�trehozott locationnek.
	 * @param sync A szinkroniz�ci�, amelyet r� szeretn�nk tenni az �lre.
	 * @param template A template, amelybe bele szeretn�nk rakni a l�trehozott locationt.
	 * @return A szinkroniz�ci� edge, amely a l�trehozott locationb�l belevezet a target locationbe.
	 * @throws Exception Ezt akkor dobja, ha az �tadott szinkorniz�ci� ? szinkorniz�ci�. Ekkor a l�trehozott strukt�ra nem m�k�dhet j�l.
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
