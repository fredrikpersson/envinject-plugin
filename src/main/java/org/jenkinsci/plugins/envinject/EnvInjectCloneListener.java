package org.jenkinsci.plugins.envinject;

import hudson.Extension;
import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import hudson.tasks.BuildWrapper;
import hudson.util.DescribableList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Clears passwords when jobs are cloned.
 */
@Extension
public class EnvInjectCloneListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(EnvInjectCloneListener.class.getName());

    @Override
    public void onCopied(Item src, Item item) {
        clearPasswords(item);
        super.onCopied(src, item);
    }

    @Override
    public void onCreated(Item item) {
        clearPasswords(item);
    }

    private void clearPasswords(Item item) {
        if (item instanceof BuildableItemWithBuildWrappers) {
            BuildableItemWithBuildWrappers buildableItemWithBuildWrappers = (BuildableItemWithBuildWrappers)item;
            DescribableList<BuildWrapper, Descriptor<BuildWrapper>> wrappersList = buildableItemWithBuildWrappers.getBuildWrappersList();
            Iterator<BuildWrapper> buildWrapperIterator = wrappersList.iterator();
            while (buildWrapperIterator.hasNext()) {
                BuildWrapper buildWrapper = buildWrapperIterator.next();

                if (buildWrapper instanceof EnvInjectPasswordWrapper) {
                    EnvInjectPasswordWrapper passwordWrapper = (EnvInjectPasswordWrapper) buildWrapper;
                    EnvInjectPasswordEntry[] oldPasswordEntries = passwordWrapper.getPasswordEntries();
                    if (oldPasswordEntries != null && oldPasswordEntries.length > 0) {
                        List<EnvInjectPasswordEntry> newPasswordEntries = new ArrayList<EnvInjectPasswordEntry>();
                        for (EnvInjectPasswordEntry passwordEntry : oldPasswordEntries) {
                            newPasswordEntries.add(new EnvInjectPasswordEntry(passwordEntry.getName(), null));
                        }
                        passwordWrapper.setPasswordEntries(newPasswordEntries
                                .toArray(new EnvInjectPasswordEntry[newPasswordEntries.size()]));
                        try {
                            buildableItemWithBuildWrappers.save();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }

}
