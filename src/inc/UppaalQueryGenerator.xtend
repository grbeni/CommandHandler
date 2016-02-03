package inc

import java.io.FileWriter
import java.io.IOException

class UppaalQueryGenerator {
	
	def static saveToQ(String filepath) {
		try {
			var fw = new FileWriter(filepath + ".q")
			val deadlockQuery = createDeadlockQuery
			val reachabilityQuery = createReachabilityQuery
			fw.write(deadlockQuery.toString + reachabilityQuery.toString)
			fw.close
			// information message, about the completion of the transformation.
			println("Query generations have been finished.")
		} catch (IOException ex) {
			System.err.println("An error occurred, while creating the q file. " + ex.message)
		}
	}
	
	def static createDeadlockQuery()'''
		A[] not deadlock
	'''
	
	def static createReachabilityQuery() '''
		«FOR locationName : Helper.getExpressionsOfLocations»E<> «locationName»
		«ENDFOR»
	'''
	
}