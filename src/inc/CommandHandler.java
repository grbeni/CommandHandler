package inc;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.yakindu.sct.model.sgraph.Statechart;

/**
 * Az oszt�ly, amely a Yakindu p�ld�nymodell alapj�n l�trehozza az UPPAAL p�ld�nymodellt.
 * F�gg a PatternMatcher �s az UppaalModeltrace.builder oszt�lyokt�l.
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

							//	Statechart statechart = (Statechart) resource.getContents().get(0);
								new YakinduToUppaalTransformer().run(resource, fileURISubstring);
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
	
}
