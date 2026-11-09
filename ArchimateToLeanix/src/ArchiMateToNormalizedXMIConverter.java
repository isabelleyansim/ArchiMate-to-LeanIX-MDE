import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.Resource.Diagnostic;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.BasicExtendedMetaData;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.ExtendedMetaData;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;

public class ArchiMateToNormalizedXMIConverter {

    public static void main(String[] args) {
        try {
            /*
             * You can either run with hardcoded paths:
             *
             * model/archimate.ecore
             * model/input.archimate
             * model/output_normalized.xmi
             *
             * Or run with 3 arguments:
             *
             * arg0 = path to archimate.ecore
             * arg1 = path to input .archimate file
             * arg2 = path to output .xmi file
             */
            File ecoreFile;
            File inputFile;
            File outputFile;

            if (args.length == 3) {
                ecoreFile = new File(args[0]);
                inputFile = new File(args[1]);
                outputFile = new File(args[2]);
            } else {
                ecoreFile = new File("model/archimate.ecore");
                inputFile = new File("model/archisurance-evaluation.archimate");
                outputFile = new File("output/archisurance-evaluation_normalized.xmi");
            }

            checkFileExists(ecoreFile, "Ecore file");
            checkFileExists(inputFile, "Input ArchiMate model");

            System.out.println("Ecore: " + ecoreFile.getAbsolutePath());
            System.out.println("Input: " + inputFile.getAbsolutePath());
            System.out.println("Output: " + outputFile.getAbsolutePath());

            ResourceSet resourceSet = new ResourceSetImpl();

            /*
             * Register resource factories.
             *
             * .ecore      -> EcoreResourceFactoryImpl
             * .archimate  -> XMLResourceFactoryImpl
             * .xmi        -> XMIResourceFactoryImpl
             */
            resourceSet.getResourceFactoryRegistry()
                    .getExtensionToFactoryMap()
                    .put("ecore", new EcoreResourceFactoryImpl());

            resourceSet.getResourceFactoryRegistry()
                    .getExtensionToFactoryMap()
                    .put("archimate", new XMLResourceFactoryImpl());

            resourceSet.getResourceFactoryRegistry()
                    .getExtensionToFactoryMap()
                    .put("xmi", new XMIResourceFactoryImpl());

            /*
             * Load and register the Archi Ecore package.
             */
            Resource ecoreResource = resourceSet.getResource(
                    URI.createFileURI(ecoreFile.getAbsolutePath()),
                    true
            );

            if (ecoreResource.getContents().isEmpty()) {
                throw new RuntimeException("The Ecore resource is empty.");
            }

            EPackage archimatePackage = (EPackage) ecoreResource.getContents().get(0);

            resourceSet.getPackageRegistry().put(
                    archimatePackage.getNsURI(),
                    archimatePackage
            );

            System.out.println();
            System.out.println("Registered Ecore package:");
            System.out.println("Name: " + archimatePackage.getName());
            System.out.println("nsURI: " + archimatePackage.getNsURI());

            /*
             * Extended metadata is important because native Archi .archimate
             * serialization uses XML names/aliases defined by the Ecore metadata.
             */
            ExtendedMetaData extendedMetaData =
                    new BasicExtendedMetaData(resourceSet.getPackageRegistry());

            Map<Object, Object> loadOptions = new HashMap<Object, Object>();
            loadOptions.put(XMLResource.OPTION_EXTENDED_META_DATA, extendedMetaData);
            loadOptions.put(XMLResource.OPTION_DEFER_IDREF_RESOLUTION, Boolean.TRUE);

            /*
             * Load the native Archi .archimate file.
             */
            Resource inputResource = resourceSet.createResource(
                    URI.createFileURI(inputFile.getAbsolutePath())
            );

            inputResource.load(loadOptions);

            printDiagnostics(inputResource, "Input resource");

            if (!inputResource.getErrors().isEmpty()) {
                throw new RuntimeException("Input model contains loading errors. Conversion stopped.");
            }

            if (inputResource.getContents().isEmpty()) {
                throw new RuntimeException("Input model has no root object.");
            }

            EObject root = inputResource.getContents().get(0);

            System.out.println();
            System.out.println("Loaded successfully.");
            System.out.println("Root class: " + root.eClass().getName());
            System.out.println("Namespace: " + root.eClass().getEPackage().getNsURI());

            /*
             * Resolve all references.
             */
            EcoreUtil.resolveAll(inputResource);

            /*
             * Check unresolved proxies after resolution.
             */
            int unresolvedProxies = countUnresolvedProxies(root);

            if (unresolvedProxies > 0) {
                throw new RuntimeException("Unresolved proxy references found: " + unresolvedProxies);
            }

            /*
             * Count all loaded object types.
             */
            Map<String, Integer> classCounts = countAllClasses(root);

            System.out.println();
            System.out.println("Loaded object types:");
            for (Map.Entry<String, Integer> entry : classCounts.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }

            /*
             * Create output XMI resource.
             *
             * We use EcoreUtil.copy(root) instead of moving root directly.
             * This keeps the original input resource intact.
             */
            Resource outputResource = resourceSet.createResource(
                    URI.createFileURI(outputFile.getAbsolutePath())
            );

            EObject copiedRoot = EcoreUtil.copy(root);
            outputResource.getContents().add(copiedRoot);

            Map<Object, Object> saveOptions = new HashMap<Object, Object>();
            saveOptions.put(XMLResource.OPTION_ENCODING, "UTF-8");
            saveOptions.put(XMLResource.OPTION_SCHEMA_LOCATION, Boolean.TRUE);

            outputResource.save(saveOptions);

            printDiagnostics(outputResource, "Output resource");

            if (!outputResource.getErrors().isEmpty()) {
                throw new RuntimeException("Output model contains saving errors.");
            }

            System.out.println();
            System.out.println("Saved normalized XMI successfully:");
            System.out.println(outputFile.getAbsolutePath());

        } catch (Exception e) {
            System.out.println();
            System.out.println("Conversion failed.");
            e.printStackTrace();
        }
    }

    private static void checkFileExists(File file, String label) {
        if (!file.exists()) {
            throw new RuntimeException(label + " does not exist: " + file.getAbsolutePath());
        }

        if (!file.isFile()) {
            throw new RuntimeException(label + " is not a file: " + file.getAbsolutePath());
        }
    }

    private static void printDiagnostics(Resource resource, String label) {
        if (!resource.getErrors().isEmpty()) {
            System.out.println();
            System.out.println(label + " errors:");
            for (Diagnostic diagnostic : resource.getErrors()) {
                System.out.println(diagnostic);
            }
        }

        if (!resource.getWarnings().isEmpty()) {
            System.out.println();
            System.out.println(label + " warnings:");
            for (Diagnostic diagnostic : resource.getWarnings()) {
                System.out.println(diagnostic);
            }
        }
    }

    private static Map<String, Integer> countAllClasses(EObject root) {
        Map<String, Integer> classCounts = new TreeMap<String, Integer>();

        count(root, classCounts);

        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext()) {
            count(iterator.next(), classCounts);
        }

        return classCounts;
    }

    private static void count(EObject object, Map<String, Integer> classCounts) {
        String className = object.eClass().getName();
        Integer current = classCounts.get(className);

        if (current == null) {
            classCounts.put(className, 1);
        } else {
            classCounts.put(className, current + 1);
        }
    }

    private static int countUnresolvedProxies(EObject root) {
        int count = 0;

        TreeIterator<EObject> iterator = root.eAllContents();

        while (iterator.hasNext()) {
            EObject object = iterator.next();

            for (EObject referencedObject : object.eCrossReferences()) {
                if (referencedObject.eIsProxy()) {
                    count++;
                    System.out.println("Unresolved proxy found in object: "
                            + object.eClass().getName()
                            + " -> "
                            + referencedObject);
                }
            }
        }

        return count;
    }
}