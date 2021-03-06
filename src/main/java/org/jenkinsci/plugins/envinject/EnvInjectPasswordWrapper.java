package org.jenkinsci.plugins.envinject;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.Launcher;
import hudson.console.LineTransformationOutputStream;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.EnvInjectLogger;
import org.jenkinsci.plugins.envinject.service.EnvInjectGlobalPasswordRetriever;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author Gregory Boissinot
 */
public class EnvInjectPasswordWrapper extends BuildWrapper {

    private static final Function<EnvInjectPasswordEntry, String> PASSWORD_ENTRY_TO_NAME = new Function<EnvInjectPasswordEntry, String> ()  {
        public String apply(EnvInjectPasswordEntry envInjectPasswordEntry) {
            return envInjectPasswordEntry.getName();
        }
    };

    private static final Function<EnvInjectPasswordEntry, String> PASSWORD_ENTRY_TO_VALUE = new Function<EnvInjectPasswordEntry, String> ()  {
        public String apply(EnvInjectPasswordEntry envInjectPasswordEntry) {
            return envInjectPasswordEntry.getValue().getPlainText();
        }
    };

    private boolean injectGlobalPasswords;
    private EnvInjectPasswordEntry[] passwordEntries;

    @DataBoundConstructor
    public EnvInjectPasswordWrapper() {
    }

    public boolean isInjectGlobalPasswords() {
        return injectGlobalPasswords;
    }

    public void setInjectGlobalPasswords(boolean injectGlobalPasswords) {
        this.injectGlobalPasswords = injectGlobalPasswords;
    }

    public EnvInjectPasswordEntry[] getPasswordEntries() {
        return passwordEntries;
    }

    public void setPasswordEntries(EnvInjectPasswordEntry[] passwordEntries) {
        this.passwordEntries = passwordEntries;
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        return new Environment() {
        };
    }

    private List<EnvInjectPasswordEntry> getEnvInjectPasswordEntries() throws EnvInjectException {

        List<EnvInjectPasswordEntry> passwordList = new ArrayList<EnvInjectPasswordEntry>();

        //--Process global passwords
        if (isInjectGlobalPasswords()) {
            EnvInjectGlobalPasswordRetriever globalPasswordRetriever = new EnvInjectGlobalPasswordRetriever();
            EnvInjectGlobalPasswordEntry[] passwordEntries = globalPasswordRetriever.getGlobalPasswords();
            if (passwordEntries != null) {
                for (EnvInjectGlobalPasswordEntry entry : passwordEntries) {
                    passwordList.add(entry);
                }
            }
        }

        //--Process job passwords
        if (getPasswordEntries() != null && getPasswordEntries().length != 0) {
            passwordList.addAll(Arrays.asList(getPasswordEntries()));
        }

        return passwordList;
    }

    @Override
    public OutputStream decorateLogger(AbstractBuild build, OutputStream outputStream) throws IOException, InterruptedException, Run.RunnerAbortedException {
        try {
            EnvInjectLogger logger = new EnvInjectLogger(new StreamTaskListener(outputStream));

            if (isInjectGlobalPasswords()) {
                logger.info("Inject global passwords.");
            }

            //--Decorate passwords
            List<String> passwords2decorate = Lists.newArrayList(Lists.transform(getEnvInjectPasswordEntries(), PASSWORD_ENTRY_TO_VALUE));

            return new EnvInjectPasswordsOutputStream(outputStream, passwords2decorate);

        } catch (EnvInjectException ee) {
            throw new Run.RunnerAbortedException();
        }
    }

    @Override
    public void makeSensitiveBuildVariables(AbstractBuild build, Set<String> sensitiveVariables) {
        try {
            sensitiveVariables.addAll(Lists.transform(getEnvInjectPasswordEntries(), PASSWORD_ENTRY_TO_NAME));
        } catch (EnvInjectException e) {
            // still better than showing secret password
            throw new RuntimeException(e);
        }
    }

    /**
     * Class took from the mask-passwords plugin
     */
    class EnvInjectPasswordsOutputStream extends LineTransformationOutputStream {

        private final OutputStream logger;
        private final Pattern passwordsAsPattern;

        EnvInjectPasswordsOutputStream(OutputStream logger, Collection<String> passwords) {

            this.logger = logger;

            if (passwords != null && passwords.size() > 0) {
                // passwords are aggregated into a regex which is compiled as a pattern
                // for efficiency
                StringBuilder regex = new StringBuilder().append('(');

                int nbMaskedPasswords = 0;
                for (String password : passwords) {
                    if (StringUtils.isNotEmpty(password)) { // we must not handle empty passwords
                        regex.append(Pattern.quote(password));
                        regex.append('|');
                        nbMaskedPasswords++;
                    }
                }
                if (nbMaskedPasswords++ >= 1) { // is there at least one password to mask?
                    regex.deleteCharAt(regex.length() - 1); // removes the last unuseful pipe
                    regex.append(')');
                    passwordsAsPattern = Pattern.compile(regex.toString());
                } else { // no passwords to hide
                    passwordsAsPattern = null;
                }
            } else { // no passwords to hide
                passwordsAsPattern = null;
            }
        }

        @Override
        protected void eol(byte[] bytes, int len) throws IOException {
            String line = new String(bytes, 0, len);
            if (passwordsAsPattern != null) {
                line = passwordsAsPattern.matcher(line).replaceAll("****");
            }
            logger.write(line.getBytes());
        }
    }

    @Override
    public void makeBuildVariables(AbstractBuild build, Map<String, String> variables) {
        try {

            //--Process global passwords
            List<EnvInjectPasswordEntry> passwordList = new ArrayList<EnvInjectPasswordEntry>();
            if (isInjectGlobalPasswords()) {
                EnvInjectGlobalPasswordRetriever globalPasswordRetriever = new EnvInjectGlobalPasswordRetriever();
                EnvInjectGlobalPasswordEntry[] passwordEntries = globalPasswordRetriever.getGlobalPasswords();
                if (passwordEntries != null) {
                    for (EnvInjectGlobalPasswordEntry entry : passwordEntries) {
                        passwordList.add(entry);
                    }
                }
            }

            //--Process job passwords
            if (getPasswordEntries() != null && getPasswordEntries().length != 0) {
                passwordList.addAll(Arrays.asList(getPasswordEntries()));
            }

            //--Inject passwords
            for (EnvInjectPasswordEntry passwordEntry : passwordList) {
                variables.put(passwordEntry.getName(), passwordEntry.getValue().getPlainText());
            }

        } catch (EnvInjectException ee) {
            throw new Run.RunnerAbortedException();
        }
    }


    @Extension
    @SuppressWarnings("unused")
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.EnvInjectPasswordWrapper_DisplayName();
        }

        @Override
        public BuildWrapper newInstance(StaplerRequest req, JSONObject formData) throws FormException {

            EnvInjectPasswordWrapper passwordWrapper = new EnvInjectPasswordWrapper();
            passwordWrapper.setInjectGlobalPasswords(formData.getBoolean("injectGlobalPasswords"));

            //Envinject passowrds
            List<EnvInjectPasswordEntry> passwordEntries = req.bindParametersToList(EnvInjectPasswordEntry.class, "envInjectPasswordEntry.");
            passwordWrapper.setPasswordEntries(passwordEntries.toArray(new EnvInjectPasswordEntry[passwordEntries.size()]));

            return passwordWrapper;
        }

        public FormValidation doCheckPassword(@QueryParameter String value) {
            if (value == null || value.isEmpty()
                    || value.equals(Secret.fromString(null).getEncryptedValue())) {
                return FormValidation.error("You must enter the password before saving");
            }
            return FormValidation.ok();

        }
    }


}
