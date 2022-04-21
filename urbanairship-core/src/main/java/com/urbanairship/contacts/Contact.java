/* Copyright Airship and Contributors */

package com.urbanairship.contacts;

import android.content.Context;

import com.urbanairship.AirshipComponent;
import com.urbanairship.AirshipComponentGroups;
import com.urbanairship.AirshipExecutors;
import com.urbanairship.Logger;
import com.urbanairship.PendingResult;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.PrivacyManager;
import com.urbanairship.UAirship;
import com.urbanairship.app.ActivityMonitor;
import com.urbanairship.app.GlobalActivityMonitor;
import com.urbanairship.app.SimpleApplicationListener;
import com.urbanairship.channel.AirshipChannel;
import com.urbanairship.channel.AirshipChannelListener;
import com.urbanairship.channel.AttributeEditor;
import com.urbanairship.channel.AttributeListener;
import com.urbanairship.channel.AttributeMutation;
import com.urbanairship.channel.ChannelRegistrationPayload;
import com.urbanairship.channel.TagGroupListener;
import com.urbanairship.channel.TagGroupsEditor;
import com.urbanairship.channel.TagGroupsMutation;
import com.urbanairship.config.AirshipRuntimeConfig;
import com.urbanairship.http.RequestException;
import com.urbanairship.http.Response;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.job.JobInfo;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonValue;
import com.urbanairship.util.CachedValue;
import com.urbanairship.util.Clock;
import com.urbanairship.util.UAStringUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.Size;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

/**
 * Airship contact. A contact is distinct from a channel and represents a "user"
 * within Airship. Contacts may be named and have channels associated with it.
 */
public class Contact extends AirshipComponent {

    @NonNull
    @VisibleForTesting
    static final String LEGACY_NAMED_USER_ID_KEY = "com.urbanairship.nameduser.NAMED_USER_ID_KEY";

    @NonNull
    @VisibleForTesting
    static final String LEGACY_ATTRIBUTE_MUTATION_STORE_KEY = "com.urbanairship.nameduser.ATTRIBUTE_MUTATION_STORE_KEY";

    @NonNull
    @VisibleForTesting
    static final String LEGACY_TAG_GROUP_MUTATIONS_KEY = "com.urbanairship.nameduser.PENDING_TAG_GROUP_MUTATIONS_KEY";

    @VisibleForTesting
    @NonNull
    static final String ACTION_UPDATE_CONTACT = "ACTION_UPDATE_CONTACT";

    /**
     * Max age for the contact subscription listing cache.
     */
    private static final long SUBSCRIPTION_CACHE_LIFETIME_MS = 10 * 60 * 1000; // 10M

    private static final String OPERATIONS_KEY = "com.urbanairship.contacts.OPERATIONS";
    private static final String LAST_RESOLVED_DATE_KEY = "com.urbanairship.contacts.LAST_RESOLVED_DATE_KEY";
    private static final String ANON_CONTACT_DATA_KEY = "com.urbanairship.contacts.ANON_CONTACT_DATA_KEY";
    private static final String LAST_CONTACT_IDENTITY_KEY = "com.urbanairship.contacts.LAST_CONTACT_IDENTITY_KEY";

    private static final long FOREGROUND_RESOLVE_INTERVAL = 24 * 60 * 60 * 1000; // 24 hours

    private final PreferenceDataStore preferenceDataStore;
    private final JobDispatcher jobDispatcher;
    private final AirshipChannel airshipChannel;
    private final PrivacyManager privacyManager;
    private final ActivityMonitor activityMonitor;
    private final Clock clock;
    private final CachedValue<Map<String, Set<Scope>>> subscriptionListCache;

    private final Object operationLock = new Object();
    private final ContactApiClient contactApiClient;
    private boolean isContactIdRefreshed = false;

    private ContactConflictListener contactConflictListener;

    private List<AttributeListener> attributeListeners = new CopyOnWriteArrayList<>();
    private List<TagGroupListener> tagGroupListeners = new CopyOnWriteArrayList<>();
    private List<ContactChangeListener> contactChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a Contact.
     *
     * @param context The application context.
     * @param preferenceDataStore The preferences data store.
     */
    public Contact(@NonNull Context context, @NonNull PreferenceDataStore preferenceDataStore,
                   @NonNull AirshipRuntimeConfig runtimeConfig, @NonNull PrivacyManager privacyManager,
                   @NonNull AirshipChannel airshipChannel) {
        this(context, preferenceDataStore, JobDispatcher.shared(context), privacyManager,
                airshipChannel, new ContactApiClient(runtimeConfig), GlobalActivityMonitor.shared(context),
                Clock.DEFAULT_CLOCK, new CachedValue<>());
    }

    /**
     * @hide
     */
    @VisibleForTesting
    Contact(@NonNull Context context, @NonNull PreferenceDataStore preferenceDataStore, @NonNull JobDispatcher jobDispatcher,
            @NonNull PrivacyManager privacyManager, @NonNull AirshipChannel airshipChannel, @NonNull ContactApiClient contactApiClient,
            @NonNull ActivityMonitor activityMonitor, @NonNull Clock clock, @NonNull CachedValue<Map<String, Set<Scope>>> subscriptionListCache) {
        super(context, preferenceDataStore);
        this.preferenceDataStore = preferenceDataStore;
        this.jobDispatcher = jobDispatcher;
        this.privacyManager = privacyManager;
        this.airshipChannel = airshipChannel;
        this.contactApiClient = contactApiClient;
        this.activityMonitor = activityMonitor;
        this.clock = clock;
        this.subscriptionListCache = subscriptionListCache;
    }

    @Override
    protected void init() {
        super.init();

        migrateNamedUser();

        activityMonitor.addApplicationListener(new SimpleApplicationListener() {
            @Override
            public void onForeground(long time) {
                if (clock.currentTimeMillis() >= getLastResolvedDate() + FOREGROUND_RESOLVE_INTERVAL) {
                    resolve();
                }
            }
        });

        airshipChannel.addChannelListener(new AirshipChannelListener() {
            @Override
            public void onChannelCreated(@NonNull String channelId) {
                if (privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
                    resolve();
                }
            }

            @Override
            public void onChannelUpdated(@NonNull String channelId) {

            }
        });

        airshipChannel.addChannelRegistrationPayloadExtender(new AirshipChannel.ChannelRegistrationPayloadExtender() {
            @NonNull
            @Override
            public ChannelRegistrationPayload.Builder extend(@NonNull ChannelRegistrationPayload.Builder builder) {
                ContactIdentity lastContactIdentity = getLastContactIdentity();
                if (lastContactIdentity != null) {
                    builder.setContactId(lastContactIdentity.getContactId());
                }
                return builder;
            }
        });

        privacyManager.addListener(new PrivacyManager.Listener() {
            @Override
            public void onEnabledFeaturesChanged() {
                checkPrivacyManager();
            }
        });

        checkPrivacyManager();
        dispatchContactUpdateJob();
    }

    private void checkPrivacyManager() {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES) || !privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            this.subscriptionListCache.invalidate();
        }

        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            ContactIdentity lastContactIdentity = getLastContactIdentity();
            if (lastContactIdentity == null) {
                return;
            }

            if (!lastContactIdentity.isAnonymous() || getAnonContactData() != null) {
                addOperation(ContactOperation.reset());
                dispatchContactUpdateJob();
            }
        }
    }

    private void migrateNamedUser() {
        if (privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            String namedUserId = preferenceDataStore.getString(LEGACY_NAMED_USER_ID_KEY, null);
            if (namedUserId != null) {
                identify(namedUserId);

                if (privacyManager.isEnabled(PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES)) {
                    JsonValue attributeJson = preferenceDataStore.getJsonValue(LEGACY_ATTRIBUTE_MUTATION_STORE_KEY);
                    List<AttributeMutation> attributeMutations = AttributeMutation.fromJsonList(attributeJson.optList());
                    attributeMutations = AttributeMutation.collapseMutations(attributeMutations);

                    JsonValue tagsJson = preferenceDataStore.getJsonValue(LEGACY_TAG_GROUP_MUTATIONS_KEY);
                    List<TagGroupsMutation> tagGroupMutations = TagGroupsMutation.fromJsonList(tagsJson.optList());
                    tagGroupMutations = TagGroupsMutation.collapseMutations(tagGroupMutations);

                    if (!attributeMutations.isEmpty() || !tagGroupMutations.isEmpty()) {
                        ContactOperation update = ContactOperation.update(tagGroupMutations, attributeMutations, null);
                        addOperation(update);
                    }
                }
            }
        }

        preferenceDataStore.remove(LEGACY_TAG_GROUP_MUTATIONS_KEY);
        preferenceDataStore.remove(LEGACY_ATTRIBUTE_MUTATION_STORE_KEY);
        preferenceDataStore.remove(LEGACY_NAMED_USER_ID_KEY);
    }

    /**
     * @hide
     */
    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @AirshipComponentGroups.Group
    public int getComponentGroup() {
        return AirshipComponentGroups.CONTACT;
    }

    /**
     * @param isEnabled {@code true} if the component is enabled, otherwise {@code false}.
     * @hide
     */
    @Override
    protected void onComponentEnableChange(boolean isEnabled) {
        super.onComponentEnableChange(isEnabled);
        if (isEnabled) {
            dispatchContactUpdateJob();
        }
    }

    /**
     * Associates the contact with the given named user identifier.
     *
     * @param externalId The channel's identifier.
     */
    public void identify(@NonNull @Size(min = 1, max = 128) String externalId) {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.debug("Contact - Contacts is disabled, ignoring contact identifying.");
            return;
        }
        addOperation(ContactOperation.identify(externalId));
        dispatchContactUpdateJob();
    }

    /**
     * Disassociate the channel from its current contact, and create a new
     * un-named contact.
     */
    public void reset() {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.debug("Contact - Contacts is disabled, ignoring contact reset.");
            return;
        }
        addOperation(ContactOperation.reset());
        dispatchContactUpdateJob();
    }

    /**
     * Edit the tags associated with this Contact.
     *
     * @return a {@link TagGroupsEditor}.
     */
    public TagGroupsEditor editTagGroups() {
        return new TagGroupsEditor() {
            @Override
            protected void onApply(@NonNull List<TagGroupsMutation> collapsedMutations) {
                super.onApply(collapsedMutations);

                if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS, PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES)) {
                    Logger.warn("Contact - Ignoring tag edits while contacts and/or tags and attributes are disabled.");
                    return;
                }

                if (collapsedMutations.isEmpty()) {
                    return;
                }

                addOperation(ContactOperation.resolve());
                addOperation(ContactOperation.updateTags(collapsedMutations));
                dispatchContactUpdateJob();
            }
        };
    }

    /**
     * Gets the named user ID.
     *
     * @return The named user ID, or null if it is unknown.
     */
    @Nullable
    public String getNamedUserId() {
        synchronized (operationLock) {
            List<ContactOperation> operations = getOperations();
            for (int i = operations.size() - 1; i >= 0; i--) {
                if (ContactOperation.OPERATION_IDENTIFY.equals(operations.get(i).getType())) {
                    ContactOperation.IdentifyPayload payload = operations.get(i).coercePayload();
                    return payload.getIdentifier();
                }
            }
            ContactIdentity identity = getLastContactIdentity();
            return identity == null ? null : identity.getNamedUserId();
        }
    }

    @Nullable
    private String getCurrentContactId() {
        synchronized (operationLock) {
            ContactIdentity identity = getLastContactIdentity();
            if (identity == null) {
                return null;
            }

            // Make sure we don't have any pending identify or reset operations
            List<ContactOperation> operations = getOperations();
            for (int i = operations.size() - 1; i >= 0; i--) {
                ContactOperation operation = operations.get(i);
                switch (operation.getType()) {
                    case ContactOperation.OPERATION_IDENTIFY:
                        ContactOperation.IdentifyPayload payload = operations.get(i).coercePayload();
                        if (!payload.getIdentifier().equals(identity.getNamedUserId())) {
                            return null;
                        }
                        break;
                    case ContactOperation.OPERATION_RESET:
                        return null;
                }
            }

            return identity.getContactId();
        }
    }

    /**
     * Registers an Email channel.
     *
     * @param address The Email address to register.
     * @param options An EmailRegistrationOptions object that defines registration options.
     */
    public void registerEmail(@NonNull String address, @NonNull EmailRegistrationOptions options) {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.warn("Contact - Ignoring Email registration while contacts are disabled.");
            return;
        }

        addOperation(ContactOperation.resolve());
        addOperation(ContactOperation.registerEmail(address, options));
        dispatchContactUpdateJob();
    }

    /**
     * Registers a Sms channel.
     *
     * @param msisdn The Mobile Station number to register.
     * @param options An SmsRegistrationObject object that defines registration options.
     */
    public void registerSms(@NonNull String msisdn, @NonNull SmsRegistrationOptions options) {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.warn("Contact - Ignoring Sms registration while contacts are disabled.");
            return;
        }

        addOperation(ContactOperation.resolve());
        addOperation(ContactOperation.registerSms(msisdn, options));
        dispatchContactUpdateJob();
    }

    /**
     * Registers an Open channel.
     *
     * @param address The address to register.
     * @param options An SmsRegistrationObject object that defines registration options.
     */
    public void registerOpenChannel(@NonNull String address, @NonNull OpenChannelRegistrationOptions options) {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.warn("Contact - Ignoring Open channel registration while contacts are disabled.");
            return;
        }

        addOperation(ContactOperation.resolve());
        addOperation(ContactOperation.registerOpenChannel(address, options));
        dispatchContactUpdateJob();
    }

    /**
     * Associates a channel to the contact.
     *
     * @param channelId The channel Id.
     * @param channelType The channel type.
     */
    public void associateChannel(@NonNull String channelId, @NonNull ChannelType channelType) {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.warn("Contact - Ignoring associate channel request while contacts are disabled.");
            return;
        }

        addOperation(ContactOperation.resolve());
        addOperation(ContactOperation.associateChannel(channelId, channelType));
        dispatchContactUpdateJob();
    }

    /**
     * Edit the attributes associated with this Contact.
     *
     * @return An {@link AttributeEditor}.
     */
    public AttributeEditor editAttributes() {
        return new AttributeEditor(clock) {
            @Override
            protected void onApply(@NonNull List<AttributeMutation> collapsedMutations) {
                if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS, PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES)) {
                    Logger.warn("Contact - Ignoring tag edits while contacts and/or tags and attributes are disabled.");
                    return;
                }

                if (collapsedMutations.isEmpty()) {
                    return;
                }

                addOperation(ContactOperation.resolve());
                addOperation(ContactOperation.updateAttributes(collapsedMutations));
                dispatchContactUpdateJob();
            }
        };
    }

    /**
     * Edits the subscription lists associated with this Contact.
     *
     * @return An {@link ScopedSubscriptionListEditor}.
     */
    public ScopedSubscriptionListEditor editSubscriptionLists() {
        return new ScopedSubscriptionListEditor(clock) {
            @Override
            protected void onApply(@NonNull List<ScopedSubscriptionListMutation> mutations) {
                if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS, PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES)) {
                    Logger.warn("Contact - Ignoring subscription list edits while contacts and/or tags and attributes are disabled.");
                    return;
                }

                if (mutations.isEmpty()) {
                    return;
                }

                addOperation(ContactOperation.resolve());
                addOperation(ContactOperation.updateSubscriptionLists(mutations));
                dispatchContactUpdateJob();
            }
        };
    }

    @VisibleForTesting
    void resolve() {
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            Logger.debug("Contact - Contacts is disabled, ignoring contact resolving.");
            return;
        }

        isContactIdRefreshed = false;
        addOperation(ContactOperation.resolve());
        dispatchContactUpdateJob();
    }

    public void setContactConflictListener(ContactConflictListener listener) {
        this.contactConflictListener = listener;
    }

    private void addOperation(@NonNull ContactOperation operation) {
        synchronized (operationLock) {
            List<ContactOperation> operations = getOperations();
            operations.add(operation);
            storeOperations(operations);
        }
    }

    @NonNull
    private List<ContactOperation> getOperations() {
        List<ContactOperation> operations = new ArrayList<>();
        synchronized (operationLock) {
            for (JsonValue value : preferenceDataStore.getJsonValue(OPERATIONS_KEY).optList()) {
                try {
                    ContactOperation operation = ContactOperation.fromJson(value);
                    operations.add(operation);
                } catch (JsonException e) {
                    Logger.error("Failed to parse contact operation", e);
                }
            }
        }

        return operations;
    }

    private void storeOperations(@NonNull List<ContactOperation> operations) {
        synchronized (operationLock) {
            preferenceDataStore.put(OPERATIONS_KEY, JsonValue.wrapOpt(operations));
        }
    }

    private void removeFirstOperation() {
        synchronized (operationLock) {
            List<ContactOperation> operations = getOperations();
            if (!operations.isEmpty()) {
                operations.remove(0);
                storeOperations(operations);
            }
        }
    }

    /**
     * Dispatches a job to update the contact.
     */
    private void dispatchContactUpdateJob() {
        dispatchContactUpdateJob(JobInfo.KEEP);
    }

    private void dispatchContactUpdateJob(@JobInfo.ConflictStrategy int conflictStrategy) {
        JobInfo jobInfo = JobInfo.newBuilder()
                                 .setAction(ACTION_UPDATE_CONTACT)
                                 .setNetworkAccessRequired(true)
                                 .setAirshipComponent(Contact.class)
                                 .setConflictStrategy(conflictStrategy)
                                 .build();

        jobDispatcher.dispatch(jobInfo);
    }

    /**
     * @hide
     */
    @Override
    @WorkerThread
    @JobInfo.JobResult
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int onPerformJob(@NonNull UAirship airship, @NonNull JobInfo jobInfo) {
        if (ACTION_UPDATE_CONTACT.equals(jobInfo.getAction())) {
            return onUpdateContact();
        }
        return JobInfo.JOB_FINISHED;
    }

    /**
     * Handles contact update job.
     *
     * @return The job result.
     */
    @JobInfo.JobResult
    @WorkerThread
    private int onUpdateContact() {
        String channelId = airshipChannel.getId();
        if (UAStringUtil.isEmpty(channelId)) {
            Logger.verbose("The channel ID does not exist. Will retry when channel ID is available.");
            return JobInfo.JOB_FINISHED;
        }

        ContactOperation nextOperation = prepareNextOperation();
        if (nextOperation == null) {
            return JobInfo.JOB_FINISHED;
        }

        try {
            Response response = performOperation(nextOperation, channelId);
            Logger.debug("Operation %s finished with response %s", nextOperation, response);
            if (response.isServerError() || response.isTooManyRequestsError()) {
                return JobInfo.JOB_RETRY;
            } else {
                removeFirstOperation();
                dispatchContactUpdateJob(JobInfo.REPLACE);
                return JobInfo.JOB_FINISHED;
            }
        } catch (RequestException e) {
            Logger.debug("Failed to update operation: %s, will retry.", e.getMessage());
            return JobInfo.JOB_RETRY;
        } catch (IllegalStateException e) {
            Logger.error("Unable to process operation %s, skipping.", nextOperation, e);
            removeFirstOperation();
            dispatchContactUpdateJob(JobInfo.REPLACE);
            return JobInfo.JOB_FINISHED;
        }
    }

    @Nullable
    private ContactOperation prepareNextOperation() {
        ContactOperation next = null;

        synchronized (operationLock) {
            List<ContactOperation> operations = getOperations();

            while (!operations.isEmpty()) {
                ContactOperation first = operations.remove(0);
                if (!shouldSkipOperation(first, true)) {
                    next = first;
                    break;
                }
            }

            if (next != null) {
                switch (next.getType()) {
                    case ContactOperation.OPERATION_UPDATE:
                        // Collapse any sequential updates (ignoring anything that can be skipped inbetween)
                        while (!operations.isEmpty()) {
                            ContactOperation nextNext = operations.get(0);

                            if (shouldSkipOperation(nextNext, false)) {
                                operations.remove(0);
                                continue;
                            }

                            if (nextNext.getType().equals(ContactOperation.OPERATION_UPDATE)) {
                                ContactOperation.UpdatePayload firstPayload = nextNext.coercePayload();
                                ContactOperation.UpdatePayload nextPayload = next.coercePayload();

                                List<TagGroupsMutation> combinedTags = new ArrayList<>();
                                combinedTags.addAll(firstPayload.getTagGroupMutations());
                                combinedTags.addAll(nextPayload.getTagGroupMutations());

                                List<AttributeMutation> combinedAttributes = new ArrayList<>();
                                combinedAttributes.addAll(firstPayload.getAttributeMutations());
                                combinedAttributes.addAll(nextPayload.getAttributeMutations());

                                List<ScopedSubscriptionListMutation> combinedSubscriptionLists = new ArrayList<>();
                                combinedSubscriptionLists.addAll(firstPayload.getSubscriptionListMutations());
                                combinedSubscriptionLists.addAll(nextPayload.getSubscriptionListMutations());

                                operations.remove(0);
                                next = ContactOperation.update(combinedTags, combinedAttributes, combinedSubscriptionLists);
                                continue;
                            }
                            break;
                        }
                        break;

                    case ContactOperation.OPERATION_IDENTIFY:
                        // Only do last identify operation if the current contact info is not anonymous (ignoring anything that can be skipped inbetween)
                        ContactIdentity contactIdentity = getLastContactIdentity();
                        if (isContactIdRefreshed && (contactIdentity == null || !contactIdentity.isAnonymous())) {
                            while (!operations.isEmpty()) {
                                ContactOperation nextNext = operations.get(0);

                                if (shouldSkipOperation(nextNext, false)) {
                                    operations.remove(0);
                                    continue;
                                }

                                if (nextNext.getType().equals(ContactOperation.OPERATION_IDENTIFY)) {
                                    next = operations.remove(0);
                                    continue;
                                }

                                break;
                            }
                        }
                        break;

                    default:
                        break;
                }
            }

            if (next != null) {
                ArrayList<ContactOperation> nextList = new ArrayList<>();
                nextList.add(next);
                nextList.addAll(operations);
                storeOperations(nextList);
            } else {
                storeOperations(operations);
            }
        }

        return next;
    }

    private boolean shouldSkipOperation(@NonNull ContactOperation operation, boolean isNext) {

        ContactIdentity contactIdentity = getLastContactIdentity();
        switch (operation.getType()) {
            case ContactOperation.OPERATION_UPDATE:
            case ContactOperation.OPERATION_REGISTER_EMAIL:
            case ContactOperation.OPERATION_REGISTER_SMS:
            case ContactOperation.OPERATION_REGISTER_OPEN_CHANNEL:
            case ContactOperation.OPERATION_ASSOCIATE_CHANNEL:
                return false;
            case ContactOperation.OPERATION_IDENTIFY:
                if (contactIdentity == null) {
                    return false;
                }
                ContactOperation.IdentifyPayload payload = operation.coercePayload();
                return isContactIdRefreshed && payload.getIdentifier().equals(contactIdentity.getNamedUserId());
            case ContactOperation.OPERATION_RESET:
                if (contactIdentity == null || !isNext) {
                    return false;
                }
                return contactIdentity.isAnonymous() && getAnonContactData() == null;

            case ContactOperation.OPERATION_RESOLVE:
                return isContactIdRefreshed;
        }
        return true;
    }

    @JobInfo.JobResult
    @WorkerThread
    @NonNull
    private Response<?> performOperation(ContactOperation operation, String channelId) throws RequestException {
        ContactIdentity lastContactIdentity = getLastContactIdentity();

        switch (operation.getType()) {
            case ContactOperation.OPERATION_UPDATE:
                if (lastContactIdentity == null) {
                    throw new IllegalStateException("Unable to process update without previous contact identity");
                }

                ContactOperation.UpdatePayload updatePayload = operation.coercePayload();
                Response<Void> updateResponse = contactApiClient.update(
                        lastContactIdentity.getContactId(),
                        updatePayload.getTagGroupMutations(),
                        updatePayload.getAttributeMutations(),
                        updatePayload.getSubscriptionListMutations()
                );

                if (updateResponse.isSuccessful() && lastContactIdentity.isAnonymous()) {
                    updateAnonData(updatePayload, null);

                    if (!updatePayload.getAttributeMutations().isEmpty()) {
                        for (AttributeListener listener : this.attributeListeners) {
                            listener.onAttributeMutationsUploaded(updatePayload.getAttributeMutations());
                        }
                    }

                    if (!updatePayload.getTagGroupMutations().isEmpty()) {
                        for (TagGroupListener listener : this.tagGroupListeners) {
                            listener.onTagGroupsMutationUploaded(updatePayload.getTagGroupMutations());
                        }
                    }
                }

                if (updateResponse.isSuccessful() && !updatePayload.getSubscriptionListMutations().isEmpty()) {
                    subscriptionListCache.invalidate();
                }

                return updateResponse;

            case ContactOperation.OPERATION_IDENTIFY:
                ContactOperation.IdentifyPayload identifyPayload = operation.coercePayload();
                String contactId = null;
                if (lastContactIdentity != null && lastContactIdentity.isAnonymous()) {
                    contactId = lastContactIdentity.getContactId();
                }

                Response<ContactIdentity> identityResponse = contactApiClient.identify(identifyPayload.getIdentifier(), channelId, contactId);
                processResponse(identityResponse, lastContactIdentity);
                return identityResponse;

            case ContactOperation.OPERATION_RESET:
                Response<ContactIdentity> resetResponse = contactApiClient.reset(channelId);
                processResponse(resetResponse, lastContactIdentity);
                return resetResponse;

            case ContactOperation.OPERATION_RESOLVE:
                Response<ContactIdentity> resolveResponse = contactApiClient.resolve(channelId);
                if (resolveResponse.isSuccessful()) {
                    setLastResolvedDate(clock.currentTimeMillis());
                }
                processResponse(resolveResponse, lastContactIdentity);
                return resolveResponse;

            case ContactOperation.OPERATION_REGISTER_EMAIL:
                if (lastContactIdentity == null) {
                    throw new IllegalStateException("Unable to process update without previous contact identity");
                }
                ContactOperation.RegisterEmailPayload registerEmailPayload = operation.coercePayload();
                Response<AssociatedChannel> registerEmailResponse = contactApiClient.registerEmail(lastContactIdentity.getContactId(), registerEmailPayload.getEmailAddress(), registerEmailPayload.getOptions());
                processResponse(registerEmailResponse);
                return registerEmailResponse;

            case ContactOperation.OPERATION_REGISTER_SMS:
                if (lastContactIdentity == null) {
                    throw new IllegalStateException("Unable to process update without previous contact identity");
                }
                ContactOperation.RegisterSmsPayload registerSmsPayload = operation.coercePayload();
                Response<AssociatedChannel> registerSmsResponse = contactApiClient.registerSms(lastContactIdentity.getContactId(), registerSmsPayload.getMsisdn(), registerSmsPayload.getOptions());
                processResponse(registerSmsResponse);
                return registerSmsResponse;

            case ContactOperation.OPERATION_REGISTER_OPEN_CHANNEL:
                if (lastContactIdentity == null) {
                    throw new IllegalStateException("Unable to process update without previous contact identity");
                }
                ContactOperation.RegisterOpenChannelPayload registerOpenChannelPayload = operation.coercePayload();
                Response<AssociatedChannel> registerOpenChannelResponse = contactApiClient.registerOpenChannel(lastContactIdentity.getContactId(), registerOpenChannelPayload.getAddress(), registerOpenChannelPayload.getOptions());
                processResponse(registerOpenChannelResponse);
                return registerOpenChannelResponse;

            case ContactOperation.OPERATION_ASSOCIATE_CHANNEL:
                if (lastContactIdentity == null) {
                    throw new IllegalStateException("Unable to process update without previous contact identity");
                }
                ContactOperation.AssociateChannelPayload associateChannelPayload = operation.coercePayload();
                Response<AssociatedChannel> associatedChannelResponse = contactApiClient.associatedChannel(lastContactIdentity.getContactId(), associateChannelPayload.getChannelId(), associateChannelPayload.getChannelType());
                processResponse(associatedChannelResponse);
                return associatedChannelResponse;

            default:
                throw new IllegalStateException("Unexpected operation type: " + operation.getType());
        }
    }

    private void processResponse(@NonNull Response<ContactIdentity> response, @Nullable ContactIdentity lastContactIdentity) {
        ContactIdentity contactIdentity = response.getResult();
        if (!response.isSuccessful() || contactIdentity == null) {
            return;
        }

        if (lastContactIdentity == null || !lastContactIdentity.getContactId().equals(contactIdentity.getContactId())) {
            if (lastContactIdentity != null && lastContactIdentity.isAnonymous()) {
                onConflict(contactIdentity.getNamedUserId());
            }

            subscriptionListCache.invalidate();
            setLastContactIdentity(contactIdentity);
            setAnonContactData(null);
            airshipChannel.updateRegistration();

            for (ContactChangeListener listener : this.contactChangeListeners) {
                listener.onContactChanged();
            }

        } else {
            ContactIdentity updated = new ContactIdentity(
                    contactIdentity.getContactId(),
                    contactIdentity.isAnonymous(),
                    contactIdentity.getNamedUserId() == null ? lastContactIdentity.getNamedUserId() : contactIdentity.getNamedUserId()
            );
            setLastContactIdentity(updated);

            if (!contactIdentity.isAnonymous()) {
                setAnonContactData(null);
            }
        }

        isContactIdRefreshed = true;
    }

    private void processResponse(@NonNull Response<AssociatedChannel> response) {
        if (response.isSuccessful() && getLastContactIdentity() != null && getLastContactIdentity().isAnonymous()) {
            updateAnonData(null, response.getResult());
        }
    }

    private void onConflict(@Nullable String namedUserId) {
        ContactConflictListener listener = this.contactConflictListener;
        if (listener == null) {
            return;
        }

        ContactData data = getAnonContactData();
        if (data == null) {
            return;
        }

        listener.onConflict(data, namedUserId);
    }

    private long getLastResolvedDate() {
        return preferenceDataStore.getLong(LAST_RESOLVED_DATE_KEY, -1);
    }

    private void setLastResolvedDate(long lastResolvedDate) {
        preferenceDataStore.put(LAST_RESOLVED_DATE_KEY, lastResolvedDate);
    }

    @Nullable
    private ContactData getAnonContactData() {
        try {
            return ContactData.fromJson(preferenceDataStore.getJsonValue(ANON_CONTACT_DATA_KEY));
        } catch (JsonException e) {
            Logger.error("Invalid contact data", e);
            preferenceDataStore.remove(ANON_CONTACT_DATA_KEY);
        }
        return null;
    }

    private void setAnonContactData(@Nullable ContactData contactData) {
        preferenceDataStore.put(ANON_CONTACT_DATA_KEY, contactData);
    }

    private void updateAnonData(@Nullable ContactOperation.UpdatePayload payload,
                                @Nullable AssociatedChannel channel) {
        Map<String, JsonValue> attributes = new HashMap<>();
        Map<String, Set<String>> tagGroups = new HashMap<>();
        List<AssociatedChannel> channels = new ArrayList<>();
        Map<String, Set<Scope>> subscriptionLists = new HashMap<>();

        ContactData anonData = getAnonContactData();
        if (anonData != null) {
            attributes.putAll(anonData.getAttributes());
            tagGroups.putAll(anonData.getTagGroups());
            channels.addAll(anonData.getAssociatedChannels());
            subscriptionLists.putAll(anonData.getSubscriptionLists());
        }

        if (payload != null) {
            for (AttributeMutation mutation : payload.getAttributeMutations()) {
                switch (mutation.action) {
                    case AttributeMutation.ATTRIBUTE_ACTION_SET:
                        attributes.put(mutation.name, mutation.value);
                        break;

                    case AttributeMutation.ATTRIBUTE_ACTION_REMOVE:
                        attributes.remove(mutation.name);
                        break;
                }
            }

            for (TagGroupsMutation mutation : payload.getTagGroupMutations()) {
                mutation.apply(tagGroups);
            }

            for (ScopedSubscriptionListMutation mutation : payload.getSubscriptionListMutations()) {
                mutation.apply(subscriptionLists);
            }
        }

        if (channel != null) {
            channels.add(channel);
        }

        ContactData data = new ContactData(attributes, tagGroups, channels, subscriptionLists);
        setAnonContactData(data);
    }

    @VisibleForTesting
    @Nullable
    ContactIdentity getLastContactIdentity() {
        JsonValue value = preferenceDataStore.getJsonValue(LAST_CONTACT_IDENTITY_KEY);
        if (!value.isNull()) {
            try {
                return ContactIdentity.fromJson(value);
            } catch (JsonException e) {
                Logger.error("Unable to parse contact identity");
            }
        }

        return null;
    }

    private void setLastContactIdentity(ContactIdentity contactIdentity) {
        preferenceDataStore.put(LAST_CONTACT_IDENTITY_KEY, JsonValue.wrap(contactIdentity));
    }

    /**
     * Adds an attribute listener.
     *
     * @param attributeListener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addAttributeListener(@NonNull AttributeListener attributeListener) {
        this.attributeListeners.add(attributeListener);
    }

    /**
     * Removes an attribute listener.
     *
     * @param attributeListener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void removeAttributeListener(@NonNull AttributeListener attributeListener) {
        this.attributeListeners.remove(attributeListener);
    }

    /**
     * Adds a tag group listener.
     *
     * @param tagGroupListener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addTagGroupListener(@NonNull TagGroupListener tagGroupListener) {
        this.tagGroupListeners.add(tagGroupListener);
    }

    /**
     * Adds a tag group listener.
     *
     * @param tagGroupListener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void removeTagGroupListener(@NonNull TagGroupListener tagGroupListener) {
        this.tagGroupListeners.remove(tagGroupListener);
    }

    /**
     * Removes a contact change listener.
     *
     * @param changeListener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addContactChangeListener(@NonNull ContactChangeListener changeListener) {
        this.contactChangeListeners.add(changeListener);
    }

    /**
     * Removes a contact change listener.
     *
     * @param changeListener The listener.
     * @hide
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void removeContactChangeListener(@NonNull ContactChangeListener changeListener) {
        this.contactChangeListeners.remove(changeListener);
    }

    /**
     * Gets any pending tag updates.
     *
     * @return The list of pending tag updates.
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<TagGroupsMutation> getPendingTagUpdates() {
        synchronized (operationLock) {
            List<TagGroupsMutation> mutations = new ArrayList<>();
            for (ContactOperation operation : getOperations()) {
                if (operation.getType().equals(ContactOperation.OPERATION_UPDATE)) {
                    ContactOperation.UpdatePayload payload = operation.coercePayload();
                    mutations.addAll(payload.getTagGroupMutations());
                }
            }
            return TagGroupsMutation.collapseMutations(mutations);
        }
    }

    /**
     * Gets any pending tag updates.
     *
     * @return The list of pending tag updates.
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<ScopedSubscriptionListMutation> getPendingSubscriptionListUpdates() {
        synchronized (operationLock) {
            List<ScopedSubscriptionListMutation> mutations = new ArrayList<>();
            for (ContactOperation operation : getOperations()) {
                if (operation.getType().equals(ContactOperation.OPERATION_UPDATE)) {
                    ContactOperation.UpdatePayload payload = operation.coercePayload();
                    mutations.addAll(payload.getSubscriptionListMutations());
                }
            }
            return ScopedSubscriptionListMutation.collapseMutations(mutations);
        }
    }

    /**
     * Gets any pending attribute updates.
     *
     * @return The list of pending attribute updates.
     * @hide
     */
    @NonNull
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public List<AttributeMutation> getPendingAttributeUpdates() {
        synchronized (operationLock) {
            List<AttributeMutation> mutations = new ArrayList<>();
            for (ContactOperation operation : getOperations()) {
                if (operation.getType().equals(ContactOperation.OPERATION_UPDATE)) {
                    ContactOperation.UpdatePayload payload = operation.coercePayload();
                    mutations.addAll(payload.getAttributeMutations());
                }
            }
            return AttributeMutation.collapseMutations(mutations);
        }
    }

    /**
     * Returns the current set of subscription lists for the current contact.
     * <p>
     * An empty set indicates that this contact is not subscribed to any lists.
     *
     * @return A {@link PendingResult} of the current set of subscription lists.
     */
    @NonNull
    public PendingResult<Map<String, Set<Scope>>> getSubscriptionLists() {
        return getSubscriptionLists(true);
    }

    /**
     * Returns the current set of subscription lists for the current contact, optionally applying pending
     * subscription list changes that will be applied during the next contact update.
     * <p>
     * An empty set indicates that this contact is not subscribed to any lists.
     *
     * @param includePendingUpdates `true` to apply pending updates to the returned set, `false` to return the set without pending updates.
     * @return A {@link PendingResult} of the current set of subscription lists.
     */
    @NonNull
    public PendingResult<Map<String, Set<Scope>>> getSubscriptionLists(final boolean includePendingUpdates) {
        final PendingResult<Map<String, Set<Scope>>> result = new PendingResult<>();

        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES) || !privacyManager.isEnabled(PrivacyManager.FEATURE_CONTACTS)) {
            result.setResult(null);
            return result;
        }

        String contactId = getCurrentContactId();
        if (contactId == null) {
            result.setResult(null);
            return result;
        }

        PendingResult<Map<String, Set<Scope>>> subscriptionListsResult = getSubscriptionLists(contactId);
        subscriptionListsResult.addResultCallback(subscriptions -> {
            if (subscriptions == null) {
                result.setResult(null);
                return;
            }

            if (!includePendingUpdates) {
                result.setResult(subscriptions);
                return;
            }

            for (ScopedSubscriptionListMutation mutation : getPendingSubscriptionListUpdates()) {
                mutation.apply(subscriptions);
            }
            result.setResult(subscriptions);
        });

        return result;
    }

    /**
     * Returns the current set of subscription lists for this contact from the cache, if available,
     * or fetches from the network, if not.
     */
    @NonNull
    private PendingResult<Map<String, Set<Scope>>> getSubscriptionLists(@NonNull String contactId) {
        final PendingResult<Map<String, Set<Scope>>> result = new PendingResult<>();

        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_TAGS_AND_ATTRIBUTES)) {
            result.setResult(null);
            return result;
        }

        Map<String, Set<Scope>> cachedSubscriptions = subscriptionListCache.get();
        if (cachedSubscriptions != null) {
            // Return subscription lists from the in-memory cache, if available.
            result.setResult(cachedSubscriptions);
        } else {
            // Otherwise, fetch the current subscriptions over the network and update the cache.
            AirshipExecutors.threadPoolExecutor().submit(() -> {
                try {
                    Response<Map<String, Set<Scope>>> response = contactApiClient.getSubscriptionLists(contactId);
                    if (response.isSuccessful()) {
                        Map<String, Set<Scope>> subscriptions = response.getResult();
                        subscriptionListCache.set(subscriptions, SUBSCRIPTION_CACHE_LIFETIME_MS);
                        result.setResult(subscriptions);
                    } else {
                        result.setResult(null);
                    }
                } catch (RequestException e) {
                    result.setResult(null);
                }
            });
        }

        return result;
    }

}
