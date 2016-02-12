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
 * Az osztály, amely a Yakindu példánymodell alapján létrehozza az UPPAAL példánymodellt.
 * Függ a PatternMatcher és az UppaalModeltrace.builder osztályoktól.
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
