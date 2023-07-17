package app.resketchware.builder.tasks;

import static android.system.OsConstants.S_IRUSR;
import static android.system.OsConstants.S_IWUSR;
import static android.system.OsConstants.S_IXUSR;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import app.resketchware.App;
import app.resketchware.R;
import app.resketchware.builder.Task;
import app.resketchware.builder.exceptions.CompilationFailedException;
import app.resketchware.builder.listeners.ProgressListener;
import app.resketchware.managers.BuiltInLibraryManager;
import app.resketchware.ui.models.BuiltInLibraryModel;
import app.resketchware.ui.models.Project;
import app.resketchware.utils.BinaryExecutor;
import app.resketchware.utils.BuiltInLibraries;
import app.resketchware.utils.ContextUtil;
import app.resketchware.utils.FileUtil;
import app.resketchware.utils.SketchwareUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class Aapt2Task extends Task {

    private File aaptBinary;
    private File compiledBuiltInLibraryResourcesDirectory;
    private String outputPath;
    private BuiltInLibraryManager libraryManager;

    public Aapt2Task(Project project, ProgressListener listener) {
        super(project, listener);
    }

    @Override
    public String getName() {
        return ContextUtil.getString(R.string.compiler_aapt2_message);
    }

    @Override
    public void prepare() throws IOException {
        aaptBinary = new File(App.getContext().getFilesDir(), "aapt2");
        outputPath = project.getBinDirectory().getAbsolutePath() + File.separator + "res";
        libraryManager = new BuiltInLibraryManager();
        compiledBuiltInLibraryResourcesDirectory = new File(App.getContext().getCacheDir(), "compiledLibs");
        FileUtil.createDirectory(outputPath);
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        maybeExtractAapt2();
        compileBuiltInLibraryResources();
        compileProjectResources();
        compileImportedResources();
        linkResources();
    }

    private void maybeExtractAapt2() throws IOException {
        String aapt2PathInAssets = "aapt" + File.separator;
        if (getAbi().toLowerCase().contains("x86")) {
            aapt2PathInAssets += "aapt2-x86";
        } else {
            aapt2PathInAssets += "aapt2-arm";
        }
        try {
            if (FileUtil.hasFileChanged(aapt2PathInAssets, aaptBinary.getAbsolutePath())) {
                Os.chmod(aaptBinary.getAbsolutePath(), S_IRUSR | S_IWUSR | S_IXUSR);
            }
        } catch (ErrnoException e) {
            Log.d("Aapt2Task", "Failed to extract AAPT2 binary");
            throw new IOException(e);
        } catch (Exception e) {
            Log.d("Aapt2Task", "Failed to extract AAPT2 binary");
            throw new IOException(e);
        }
    }

    private void compileProjectResources() throws CompilationFailedException {
        checkForExist(project.getResDirectory());

        ArrayList<String> commands = new ArrayList<>();
        commands.add(aaptBinary.getAbsolutePath());
        commands.add("compile");
        commands.add("--dir");
        commands.add(project.getResDirectory().getAbsolutePath());
        commands.add("-o");
        commands.add(outputPath + File.separator + "project.zip");

        Log.d("Aapt2Task" + ":projectResources", commands.toString());
        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(commands);
        if (!executor.execute().isEmpty()) {
            throw new CompilationFailedException(executor.getLog());
        }
    }

    private void compileBuiltInLibraryResources() throws CompilationFailedException {
        compiledBuiltInLibraryResourcesDirectory.mkdirs();
        for (BuiltInLibraryModel library : libraryManager.getLibraries()) {
            if (library.hasResources()) {
                File cachedCompiledResources = new File(compiledBuiltInLibraryResourcesDirectory, library.getName() + ".zip");
                String libraryResources = BuiltInLibraries.getLibraryResourcesPath(library.getName());

                checkForExist(libraryResources);

                if (isBuiltInLibraryRecompilingNeeded(cachedCompiledResources)) {
                    ArrayList<String> commands = new ArrayList<>();
                    commands.add(aaptBinary.getAbsolutePath());
                    commands.add("compile");
                    commands.add("--dir");
                    commands.add(libraryResources);
                    commands.add("-o");
                    commands.add(cachedCompiledResources.getAbsolutePath());

                    Log.d("Aapt2Task" + ":builtInLibraryResources", commands.toString());
                    BinaryExecutor executor = new BinaryExecutor();
                    executor.setCommands(commands);
                    if (!executor.execute().isEmpty()) {
                        throw new CompilationFailedException(executor.getLog());
                    }
                } else {
                    Log.d("Aapt2Task" + ":builtInLibraryResources", "Skipped resource recompilation for built-in library " + library.getName());
                }
            }
        }
    }

    private boolean isBuiltInLibraryRecompilingNeeded(File cachedCompiledResources) {
        if (cachedCompiledResources.exists()) {
            try {
                Context context = App.getContext();
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                        .lastUpdateTime > cachedCompiledResources.lastModified();
            } catch (PackageManager.NameNotFoundException e) {
                Log.e("Aapt2Task" + ":isBuiltInLibraryRecompiling", "Couldn't get package info about ourselves: " + e.getMessage(), e);
            }
        } else {
            Log.d("Aapt2Task" + ":isBuiltInLibraryRecompiling", "File " + cachedCompiledResources.getAbsolutePath()
                    + " doesn't exist, forcing compilation");
        }
        return true;
    }

    private void compileImportedResources() throws CompilationFailedException {
        if (!FileUtil.isExists(project.getResourceDirectory()) && project.getResourceDirectory().length() == 0) {
            return;
        }

        ArrayList<String> commands = new ArrayList<>();
        commands.add(aaptBinary.getAbsolutePath());
        commands.add("compile");
        commands.add("--dir");
        commands.add(project.getResourceDirectory().getAbsolutePath());
        commands.add("-o");
        commands.add(outputPath + File.separator + "project-imported.zip");

        Log.d("Aapt2Task" + ":importedResources", commands.toString());
        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(commands);
        if (!executor.execute().isEmpty()) {
            throw new CompilationFailedException(executor.getLog());
        }
    }

    private void linkResources() throws CompilationFailedException {
        listener.post(ContextUtil.getString(R.string.compiler_aapt2_linking_message));

        ArrayList<String> args = new ArrayList<>();
        args.add(aaptBinary.getAbsolutePath());
        args.add("link");
        args.add("--allow-reserved-package-id");
        args.add("--auto-add-overlay");
        args.add("--no-version-vectors");
        args.add("--no-version-transitions");
        args.add("--min-sdk-version");
        args.add(String.valueOf(projectSettings.getMinSdkVersion()));
        args.add("--target-sdk-version");
        args.add(String.valueOf(projectSettings.getTargetSdkVersion()));
        args.add("--version-code");
        args.add(project.getVersionCode());
        args.add("--version-name");
        args.add(project.getVersionName());
        args.add("-I");
        args.add(SketchwareUtil.getBootstrapFile().getAbsolutePath());

        for (BuiltInLibraryModel library : libraryManager.getLibraries()) {
            if (library.hasAssets()) {
                String assetsPath = BuiltInLibraries.getLibraryAssetPath(library.getName());

                checkForExist(new File(assetsPath));
                args.add("-A");
                args.add(assetsPath);
            }
        }

        checkForExist(project.getAssetsDirectory());
        args.add("-A");
        args.ads(project.getAssetsDirectory().getAbsolutePath());

        File projectArchive = new File(project.getResourceDirectory().getAbsolutePath(), "project.zip");
        if (FileUtil.isExists(projectArchive)) {
            args.add("-R");
            args.add(projectArchive.getAbsolutePath());
        }

        File importedArchive = new File(project.getResourceDirectory().getAbsolutePath(), "project-imported.zip");
        if (FileUtil.isExists(importedArchive)) {
            args.add("-R");
            args.add(importedArchive.getAbsolutePath());
        }

        checkForExist(project.getRJavaDirectory());
        args.add("--java");
        args.add(project.getRJavaDirectory().getAbsolutePath());

        checkForExist(project.getAndroidManifest());
        args.add("--manifest");
        args.add(project.getAndroidManifest().getAbsolutePath());

        args.add("-o");
        args.add(project.getResourcesApkDirectory().getAbsolutePath());

        Log.d("Aapt2Task" + ":link", args.toString());
        BinaryExecutor executor = new BinaryExecutor();
        executor.setCommands(args);
        if (!executor.execute().isEmpty()) {
            throw new CompilationFailedException(executor.getLog());
        }
    }

    private void checkForExist(File file) throws CompilationFailedException {
        if (!FileUtil.isExists(file)) {
            throw new CompilationFailedException("No such file or directory: " + file.getAbsolutePath());
        }
    }

    private String getAbi() {
        if (Build.VERSION.SDK_INT >= 21) {
            String[] supportedAbis = Build.SUPPORTED_ABIS;
            if (supportedAbis != null) {
                return supportedAbis[0];
            }
        }
        return Build.CPU_ABI;
    }
}