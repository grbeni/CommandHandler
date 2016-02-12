package inc

import org.eclipse.incquery.runtime.api.IncQueryEngine
import org.yakindu.sct.model.sgraph.Region
import de.uni_paderborn.uppaal.templates.Template
import org.eclipse.incquery.runtime.api.impl.RunOnceQueryEngine
import java.util.Map
import de.uni_paderborn.uppaal.templates.Location
import org.yakindu.sct.model.sgraph.Vertex
import org.yakindu.sct.model.sgraph.Transition
import de.uni_paderborn.uppaal.templates.Edge
import hu.bme.mit.inf.alf.uppaal.transformation.UppaalModelBuilder
import org.eclipse.emf.ecore.resource.Resource
import java.util.HashMap
import org.eclipse.xtend.lib.annotations.Accessors

class Trace {
	
	// IncQuery engines
	@Accessors IncQueryEngine engine;
	@Accessors RunOnceQueryEngine runOnceEngine;
	 
	// Uppaal variable names
	@Accessors val syncChanVar = "syncChan";
	@Accessors val isActiveVar = "isActive";
	@Accessors val clockVar = "Timer";
	@Accessors val endVar = "end";
			
	// For the building of the Uppaal model
	@Accessors UppaalModelBuilder builder = null;	
			
	// A Map for Yakindu:Region -> UPPAAL:Template mapping									 								
	@Accessors Map<Region, Template> regionTemplateMap = null;
			
	// A Map for Yakindu:Vertex -> UPPAAL:Location mapping									 								
	@Accessors Map<Vertex, Location> stateLocationMap = null;
			
	// A Map for Yakindu:Transition -> UPPAAL:Edge mapping
	@Accessors Map<Transition, Edge> transitionEdgeMap = null;
			
	// A Map for Yakindu:Vertex -> UPPAAL:Edge mapping
	// Returns the Uppaal edge going to the location equivalent of the Yakindu vertex going from the entryLocation
	// (Entry event or composite states.)
	@Accessors Map<Vertex, Edge> hasEntryLoc = null;

	// A Map containg the outgoing edge of the trigger location
	@Accessors Map<Transition, Edge> hasTriggerPlusEdge = null;
	
	// A Map containg the generated init location of a template
	@Accessors Map<Template, Location> hasInitLoc = null;
			
	// For the generation of sync channels
	@Accessors int syncChanId = 0;
	// For the generation of entry locations
	@Accessors int entryStateId = 0;
	// For the generation of raising locations
	@Accessors int raiseId = 0;
	
	new(Resource resource) {
		// IncQuery engine initialization
		engine = IncQueryEngine.on(resource);
		runOnceEngine = new RunOnceQueryEngine(resource);
		
		// UPPAAL model initialization
		builder = UppaalModelBuilder.getInstance();
		builder.createNTA("YakinduToUppaalNTA");
									
		// Map initialization
		regionTemplateMap = new HashMap<Region, Template>();									 								
		stateLocationMap = new HashMap<Vertex, Location>();
		transitionEdgeMap = new HashMap<Transition, Edge>();
		hasEntryLoc = new HashMap<Vertex, Edge>();
		hasTriggerPlusEdge = new HashMap<Transition, Edge>();
		hasInitLoc = new HashMap<Template, Location>();
	}
		
}