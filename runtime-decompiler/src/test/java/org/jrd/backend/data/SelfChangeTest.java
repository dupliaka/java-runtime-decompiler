package org.jrd.backend.data;

import org.jrd.backend.core.AgentRequestAction;
import org.jrd.backend.core.ClassInfo;
import org.jrd.backend.core.VmDecompilerStatus;
import org.jrd.frontend.frame.main.DecompilationController;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SelfChangeTest {

    @Test
    void assertUnchangedClassInfoPrintsAsExpected() throws Exception {
        ClassInfo ci = new ClassInfo("dummy", null, null);
        Assertions.assertEquals("dummy", ci.getName());
    }

    /**
     *  WARNING WARNING WARNING WARNING WARNING WARNING WARNING WARNING WARNING WARNING
     *  this test is chagin jrd under hands. may break whole universe
     *  WARNING WARNING WARNING WARNING WARNING WARNING WARNING WARNING WARNING WARNING
     * @throws Exception
     */
    @Test
    void assertChangedClassInfoPrintsAsForced() throws Exception {
        ClassInfo orig1 = new ClassInfo("dummy", null, null);
        Assertions.assertEquals("dummy", orig1.getName());
        //shit, not working
        //System.setProperty("jdk.attach.allowAttachSelf", "true");
        AbstractAgentNeedingTest.setupAgentLocations();
        Model model = new Model(); // must be below dummy process execution to be aware of it during VmManager instantiation
        model.getVmManager().updateLocalVMs();
        String stringPid = "" + ProcessHandle.current().pid();
        VmInfo vmInfo = model.getVmManager().findVmFromPidNoException(stringPid);
        ClassInfo[] classes = Cli.obtainClassesDetails(vmInfo, model.getVmManager());
        String loadedOrig = null;
        for (ClassInfo ci : classes) {
            if (ci.getName().equals(ClassInfo.class.getName())) {
                loadedOrig = ci.getLocation();
            }
        }
        Assertions.assertNotNull(loadedOrig);
        String classInfoSrc = Files.readString(new File(System.getProperty("user.dir") + "/src/main/java/org/jrd/backend/core/ClassInfo.java").toPath());
        String changedSrc = classInfoSrc.replaceFirst("        return name;", "        return \"no way\";");
        File changedSrcFile = File.createTempFile("jrdTest","class.java");
        Files.write(changedSrcFile.toPath(), changedSrc.getBytes(StandardCharsets.UTF_8));
        File compiled = CompileUploadCliTest.compile(null, stringPid, model, changedSrcFile);
        CompileUploadCliTest.overwrite(stringPid, ClassInfo.class.getName(), model, compiled);

        //Assertions.assertEquals("dummy", orig1.getName());  //something in ^ enforced overwritten class to take effect
        ClassInfo modified1 = new ClassInfo("dummy", null, null);
        Assertions.assertNotEquals("dummy", modified1.getName());
        Assertions.assertEquals("no way", modified1.getName());
        Assertions.assertEquals("no way", orig1.getName()); //!!

        AgentRequestAction requestList1 = DecompilationController.createRequest(vmInfo, AgentRequestAction.RequestAction.OVERRIDES);
        String response1 = DecompilationController.submitRequest(model.getVmManager(), requestList1);
        String[] list1 = vmInfo.getVmDecompilerStatus().getLoadedClassNames();//note, that  the loaded string is nonsense, because we overridd the class definiton of bearer class
        //however the overrid is removed properly, because it is doen in agent o strings
        //but the number of overrides is correct
        Assertions.assertEquals(1, list1.length);
        Assertions.assertEquals("no way", list1[0]);
        AgentRequestAction request = DecompilationController.createRequest(vmInfo, AgentRequestAction.RequestAction.REMOVE_OVERRIDES, ".*");
        String response = DecompilationController.submitRequest(model.getVmManager(), request);
        Assertions.assertEquals("ok", response);
        AgentRequestAction requestList2 = DecompilationController.createRequest(vmInfo, AgentRequestAction.RequestAction.OVERRIDES);
        String response2 = DecompilationController.submitRequest(model.getVmManager(), requestList2);
        String[] list2 =  vmInfo.getVmDecompilerStatus().getLoadedClassNames(); //this content is correct, nothing found
        Assertions.assertEquals(0, list2.length);
        //right now, the removeal of overrid had not yet taken palce, becase trasnform was not called
        ClassInfo restored1 = new ClassInfo("dummy", null, null);
        Assertions.assertEquals("no way", restored1.getName());
        //lets eforce some transform
        VmDecompilerStatus r = Cli.obtainClass(vmInfo, ClassInfo.class.getName(), model.getVmManager());
        ClassInfo restored2 = new ClassInfo("dummy", null, null);
        Assertions.assertEquals("dummy", restored2.getName());
        Assertions.assertEquals("dummy", restored1.getName());
    }
}
