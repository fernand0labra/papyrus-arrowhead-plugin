package setup.registry.handlers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Pattern;
import org.eclipse.jface.window.Window;
import org.apache.commons.io.FileUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import common.utils.CodgenUtil;

import java.sql.*;

/**
 * 
 * The class checks the Java/Maven/MySQL requirements and creates an empty Arrowhead database and user
 * 
 * @author fernand0labra
 *
 */
public class DatabaseDeployment {
	
	// =================================================================================================
	// attributes
	
	private static Properties configuration = CodgenUtil.getProperties("WorkSpaceConfiguration");
	private String workspace = configuration.getProperty("workspace");
	
	// =================================================================================================
	// methods

	// -------------------------------------------------------------------------------------------------
	@Execute
	public void execute(Shell shell) throws Exception {
		MessageBox messageBox = null;

		// Check java/maven/mysql requirements
		String[] versionCheck = { "11#java -version#Java#2", "3.5#mvn -version#Maven#2", "5.7#mysql -V#MySQL#3" };
		for (String requirement : versionCheck) {
			float requiredVersion = Float.parseFloat(requirement.split("#")[0]);
			String command = requirement.split("#")[1];
			String library = requirement.split("#")[2];
			int position = Integer.parseInt(requirement.split("#")[3]);

			ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
			builder.redirectErrorStream(true);
			Process p = builder.start();

			BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String[] splittedVersion = r.readLine().split(" ")[position].replaceAll("\"", "").split(Pattern.quote("."));
			float installedVersion = Float.parseFloat(splittedVersion[0] + "." + splittedVersion[1]);

			if (installedVersion < requiredVersion) { // Get version and compare
				String errorMessage = "The required version of " + library + " is " + String.valueOf(requiredVersion);
				messageBox = new MessageBox(new Shell(), SWT.ERROR);
				messageBox.setMessage(errorMessage);
				messageBox.open();

				throw new Exception(errorMessage);
			}
		}

		DialogWindow dialog = new DialogWindow(shell);
		Connection conn = null;

		if (dialog.open() == Window.OK) {
			String rootUser = DialogWindow.getRootUser();
			String rootPassword = DialogWindow.getRootPassword();
			String host = DialogWindow.getHost();

			FileUtils.forceMkdir(new File(workspace + "\\.temp\\"));

			final ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(DatabaseDeployment.class.getClassLoader());

			// Initialise Velocity Engine
			VelocityEngine velocityEngine = new VelocityEngine();
			velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
			velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
			velocityEngine.init();

			// try { // Connect and check if the arrowhead database exists
			// DriverManager.getConnection("jdbc:mysql://" + host + ":3306/arrowhead",
			// rootUser, rootPassword);
			// } catch (Exception noArrowheadDatabase) { // No Arrowhead Database

			// Database creation script generation
			// TODO Change with online installation
			String[] scriptDirectories = { "deploy", "core", "support" };
			HashMap<String, String> files = new HashMap<String, String>();
			files.put(scriptDirectories[0], "createArrowheadTables#createEmptyArrowheadDB");
			files.put(scriptDirectories[1], "authorizationPrivileges#orchestratorPrivileges#serviceRegistryPrivileges");
			files.put(scriptDirectories[2], ""
					+ "certificateAuthorityPrivileges#choreographerPrivileges#configurationPrivileges#datamanagerPrivileges#"
					+ "deviceRegistryPrivileges#eventHandlerPrivileges#eventHandlerPrivileges#gamsPrivileges#"
					+ "gatekeeperPrivileges#gatewayPrivileges#mscvPrivileges#onboardingControllerPrivileges#"
					+ "plantDescriptionEnginePrivileges#qosMonitorPrivileges#systemRegistryPrivileges#timemanagerPrivileges#"
					+ "translatorPrivileges");

			for (String directory : scriptDirectories)
				for (String file : files.get(directory).split("#"))
					FileUtils.copyURLToFile(this.getClass().getResource("/scripts/" + directory + "/" + file + ".sql"),
							new File(workspace + "\\.temp\\" + file + ".sql"));

			// Database creation script execution
			String command = "cd " + workspace + "\\.temp\\" + "& mysql --user=" + rootUser + " --password="
					+ rootPassword + " --host=" + host + " < " + "createEmptyArrowheadDB.sql";
			new ProcessBuilder("cmd.exe", "/c", command).start();

			messageBox = new MessageBox(new Shell(), SWT.ICON_INFORMATION);
			messageBox.setMessage("Database created correctly");
			messageBox.open();
			// }

			String user = DialogWindow.getUser();
			String password = DialogWindow.getPassword();

			try {
				conn = DriverManager.getConnection("jdbc:mysql://" + host + ":3306/arrowhead", user, password);
			} catch (Exception noArrowheadUser) { // Non existing user
				// User creation query generation
				Template template = velocityEngine.getTemplate("templates/createArrowheadUser.vm");
				VelocityContext context = new VelocityContext();
				context.put("user", user);
				context.put("password", password);
				context.put("host", host);

				Writer writer = new FileWriter(new File(workspace + "\\.temp\\createArrowheadUser.sql"));
				template.merge(context, writer);
				writer.flush();
				writer.close();

				// User creation query execution
				command = "mysql --user=" + rootUser + " --password=" + rootPassword + " --host=" + host + " < "
						+ workspace + "\\.temp\\" + "createArrowheadUser.sql";
				new ProcessBuilder("cmd.exe", "/c", command).start();

				messageBox = new MessageBox(new Shell(), SWT.ICON_INFORMATION);
				messageBox.setMessage("User created correctly");
				messageBox.open();
			}

			// FileUtils.forceDelete(new File(workspace + "\\.temp\\"));

			// Set back default class loader
			Thread.currentThread().setContextClassLoader(oldContextClassLoader);
		}
	}

}
