package io.openems.edge.core.appmanager;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentServiceObjects;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;

import io.openems.common.exceptions.OpenemsError;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.function.ThrowingFunction;
import io.openems.common.function.ThrowingSupplier;
import io.openems.common.jsonrpc.base.GenericJsonrpcResponseSuccess;
import io.openems.common.jsonrpc.base.JsonrpcRequest;
import io.openems.common.jsonrpc.base.JsonrpcResponseSuccess;
import io.openems.common.jsonrpc.request.UpdateComponentConfigRequest;
import io.openems.common.jsonrpc.request.UpdateComponentConfigRequest.Property;
import io.openems.common.session.Role;
import io.openems.common.utils.JsonUtils;
import io.openems.common.utils.Mutex;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.JsonApi;
import io.openems.edge.common.user.User;
import io.openems.edge.core.appmanager.dependency.AppManagerAppHelper;
import io.openems.edge.core.appmanager.dependency.Dependency;
import io.openems.edge.core.appmanager.dependency.UpdateValues;
import io.openems.edge.core.appmanager.jsonrpc.AddAppInstance;
import io.openems.edge.core.appmanager.jsonrpc.DeleteAppInstance;
import io.openems.edge.core.appmanager.jsonrpc.GetApp;
import io.openems.edge.core.appmanager.jsonrpc.GetAppAssistant;
import io.openems.edge.core.appmanager.jsonrpc.GetAppDescriptor;
import io.openems.edge.core.appmanager.jsonrpc.GetAppInstances;
import io.openems.edge.core.appmanager.jsonrpc.GetApps;
import io.openems.edge.core.appmanager.jsonrpc.UpdateAppInstance;
import io.openems.edge.core.appmanager.validator.Validator;
import io.openems.edge.core.componentmanager.ComponentManagerImpl;

@Designate(ocd = Config.class, factory = false)
@Component(//
		name = AppManager.SINGLETON_SERVICE_PID, //
		immediate = true, //
		property = { //
				"enabled=true" //
		})
public class AppManagerImpl extends AbstractOpenemsComponent
		implements AppManager, OpenemsComponent, JsonApi, ConfigurationListener {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	private final AppValidateWorker worker;
	private final AppInstallWorker appInstallWorker;
	private final AppSynchronizeWorker appSynchronizeWorker;

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.DYNAMIC)
	protected volatile List<OpenemsApp> availableApps;

	@Reference
	private ComponentServiceObjects<AppManagerAppHelper> csoAppManagerAppHelper;

	@Reference
	protected ComponentManager componentManager;

	@Reference
	protected ComponentUtil componentUtil;

	@Reference
	protected Validator validator;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected AppCenterBackendUtil backendUtil;

	protected final List<OpenemsAppInstance> instantiatedApps = new ArrayList<>();

	/**
	 * Blocks until all changes to an app have been applied to the list of
	 * instantiatedApps and the appManagerConfiguration.
	 */
	private boolean aquiredMutex = false;
	private final Mutex mutexForUpdatingConfig = new Mutex(true);
	private UpdateValues lastUpdate = null;

	public AppManagerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				AppManager.ChannelId.values() //
		);
		this.worker = new AppValidateWorker(this);
		this.appInstallWorker = new AppInstallWorker(this);
		this.appSynchronizeWorker = new AppSynchronizeWorker(this);
	}

	@Activate
	protected void activate(ComponentContext componentContext, Config config) {
		super.activate(componentContext, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);
		this.applyConfig(config);

		this.worker.activate(this.id());
		this.appInstallWorker.activate(this.id());
		this.appSynchronizeWorker.activate(this.id());

		this.appInstallWorker.setKeyForFreeApps(config.keyForFreeApps());

		// resolve dependencies
		CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES) //
				.execute(new ResolveDependencies(componentContext.getBundleContext()));

		if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
			return;
		}
	}

	@Modified
	protected void modified(ComponentContext componentContext, Config config) throws OpenemsNamedException {
		super.modified(componentContext, SINGLETON_COMPONENT_ID, SINGLETON_SERVICE_PID, true);
		this.applyConfig(config);

		this.worker.modified(this.id());
		this.appInstallWorker.modified(this.id());
		this.appSynchronizeWorker.modified(this.id());

		this.appInstallWorker.setKeyForFreeApps(config.keyForFreeApps());
		this.worker.triggerNextRun();

		if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
		this.worker.deactivate();
		this.appInstallWorker.deactivate();
		this.appSynchronizeWorker.deactivate();
	}

	@Override
	public void _setAppsNotSyncedWithBackend(boolean value) {
		AppManager.super._setAppsNotSyncedWithBackend(value);
		if (value) {
			this.appSynchronizeWorker.triggerNextRun();
		}
	}

	/**
	 * Gets an unmodifiable list of the current instantiated apps.
	 *
	 * @return the list of instantiated apps
	 */
	public final List<OpenemsAppInstance> getInstantiatedApps() {
		return Collections.unmodifiableList(new ArrayList<>(this.instantiatedApps));
	}

	/**
	 * formats the given apps into a JSON array string.
	 *
	 * @param apps that should be formated
	 * @return formated apps string
	 */
	private static String getJsonAppsString(List<OpenemsAppInstance> apps) {
		return JsonUtils
				.prettyToString(apps.stream().map(OpenemsAppInstance::toJsonObject).collect(JsonUtils.toJsonArray()));
	}

	/**
	 * Parses the configured apps to a List of {@link OpenemsAppInstance}s.
	 *
	 * @param apps the app configuration from Config.json as {@link JsonArray}
	 * @return List of {@link OpenemsAppInstance}s
	 * @throws OpenemsNamedException on parse error
	 */
	private static List<OpenemsAppInstance> parseInstantiatedApps(JsonArray apps) throws OpenemsNamedException {
		var errors = new ArrayList<String>();
		var result = new ArrayList<OpenemsAppInstance>(apps.size());
		for (var appElement : apps) {
			var json = JsonUtils.getAsJsonObject(appElement);
			var appId = JsonUtils.getAsString(json, "appId");
			var instanceId = JsonUtils.getAsUUID(json, "instanceId");
			var alias = JsonUtils.getAsString(json, "alias");
			var properties = JsonUtils.getAsJsonObject(json, "properties");
			if (result.stream().anyMatch(t -> t.instanceId.equals(instanceId))) {
				errors.add("App with ID[" + instanceId + "] already exists!");
				continue;
			}
			List<Dependency> dependecies = null;
			if (json.has("dependencies")) {
				dependecies = new LinkedList<>();
				var dependecyArray = json.get("dependencies").getAsJsonArray();
				for (var i = 0; i < dependecyArray.size(); i++) {
					var dependecyJson = dependecyArray.get(i).getAsJsonObject();
					var dependecy = new Dependency(dependecyJson.get("key").getAsString(),
							JsonUtils.getAsUUID(dependecyJson, "instanceId"));
					dependecies.add(dependecy);
				}
			}
			result.add(new OpenemsAppInstance(appId, alias, instanceId, properties, dependecies));
		}
		if (!errors.isEmpty()) {
			throw new OpenemsException(errors.stream().collect(Collectors.joining("|")));
		}
		return result;
	}

	private synchronized void applyConfig(Config config) {
		boolean isResultOfRpcRequest = false;
		synchronized (this.instantiatedApps) {
			try {
				var apps = config.apps();
				if (apps.isBlank()) {
					apps = "[]"; // default to empty array
				}
				try {
					var instApps = parseInstantiatedApps(JsonUtils.parseToJsonArray(apps));

					// always replace old apps with the new ones
					var currentApps = new ArrayList<>(this.instantiatedApps);

					// if equal the applyConfig is because of a
					// installation/modification/removing of an app via JsonrpcRequest
					if (currentApps.containsAll(instApps)//
							&& instApps.size() == currentApps.size()) {
						isResultOfRpcRequest = true;
					} else if (this.lastUpdate != null //
							&& (!instApps.containsAll(this.lastUpdate.modifiedOrCreatedApps) //
									|| instApps.stream().anyMatch(t -> this.lastUpdate.deletedApps.stream() //
											.anyMatch(o -> o.equals(t))))) {
						// the last update was not applied
						this.logWarn(this.log, "Modified AppManager config properties directly. " //
								+ "If there was an installation/modification/deinstallation of an App " //
								+ "running there might be lost configuration changes. Expected: " //
								+ "Installed/Modified: " //
								+ JsonUtils.prettyToString(this.lastUpdate.modifiedOrCreatedApps.stream() //
										.map(OpenemsAppInstance::toJsonObject) //
										.collect(JsonUtils.toJsonArray()))
								+ Optional.ofNullable(this.lastUpdate.deletedApps) //
										.map(deletedApps -> {
											return System.lineSeparator() + "Removed: " //
													+ JsonUtils.prettyToString(this.lastUpdate.deletedApps.stream() //
															.map(OpenemsAppInstance::toJsonObject) //
															.collect(JsonUtils.toJsonArray()));
										}).orElse(""));
					}

					this.instantiatedApps.clear();
					this.instantiatedApps.addAll(instApps);

					this._setWrongAppConfiguration(false);

				} catch (OpenemsNamedException e) {
					this._setWrongAppConfiguration(true);
					e.printStackTrace();
					return;
				}
			} finally {
				if (isResultOfRpcRequest && this.aquiredMutex) {
					this.lastUpdate = null;
					this.aquiredMutex = false;
					this.mutexForUpdatingConfig.release();
				}
			}
		}
	}

	@Override
	public void configurationEvent(ConfigurationEvent event) {
		this.worker.configurationEvent(event);
	}

	/**
	 * Gets a filter for excluding instances.
	 *
	 * @param excludingInstanceIds the instances that should be excluded
	 * @return the filter
	 */
	public static Predicate<? super OpenemsAppInstance> excludingInstanceIds(UUID... excludingInstanceIds) {
		return i -> !Arrays.stream(excludingInstanceIds).anyMatch(id -> id.equals(i.instanceId));
	}

	/**
	 * Gets an {@link Iterable} that loops thru every existing app instance and its
	 * configuration.
	 *
	 * @return the {@link Iterable}
	 */
	public Iterable<Entry<OpenemsAppInstance, AppConfiguration>> appConfigs() {
		return this.appConfigs(null);
	}

	/**
	 * Gets an {@link Iterable} that loops thru every existing app instance and its
	 * configuration.
	 *
	 * @param filter the filter that gets applied to the instances
	 * @return the {@link Iterable}
	 */
	public Iterable<Entry<OpenemsAppInstance, AppConfiguration>> appConfigs(
			Predicate<? super OpenemsAppInstance> filter) {
		return this.appConfigs(this.instantiatedApps, filter);
	}

	/**
	 * Gets an {@link Iterable} that loops thru every instance and its
	 * configuration.
	 *
	 * @param instances the instances
	 * @param filter    the filter that gets applied to the instances
	 * @return the {@link Iterable}
	 */
	public Iterable<Entry<OpenemsAppInstance, AppConfiguration>> appConfigs(List<OpenemsAppInstance> instances,
			Predicate<? super OpenemsAppInstance> filter) {
		return new Iterable<>() {
			@Override
			public Iterator<Entry<OpenemsAppInstance, AppConfiguration>> iterator() {
				return AppManagerImpl.this.appConfigIterator(instances, filter);
			}
		};
	}

	/**
	 * Gets an {@link Iterator} that loops thru every instance and its
	 * configuration.
	 *
	 * @param instances the instances
	 * @param filter    the filter that gets applied to the instances
	 * @return the {@link Iterator}
	 */
	private Iterator<Entry<OpenemsAppInstance, AppConfiguration>> appConfigIterator(List<OpenemsAppInstance> instances,
			Predicate<? super OpenemsAppInstance> filter) {
		List<OpenemsAppInstance> actualInstances = instances.stream().filter(i -> filter == null || filter.test(i)) //
				.collect(Collectors.toList());
		return new Iterator<>() {

			private final Iterator<OpenemsAppInstance> instanceIterator = actualInstances.iterator();

			private OpenemsAppInstance nextInstance = null;
			private AppConfiguration nextConfiguration = null;

			@Override
			public Entry<OpenemsAppInstance, AppConfiguration> next() {
				var returnValue = new AbstractMap.SimpleEntry<>(this.nextInstance, this.nextConfiguration);
				this.nextInstance = null;
				this.nextConfiguration = null;
				return returnValue;
			}

			@Override
			public boolean hasNext() {
				// value not obtained
				if (this.nextConfiguration != null) {
					return true;
				}
				while (this.instanceIterator.hasNext() && this.nextConfiguration == null) {
					this.nextInstance = this.instanceIterator.next();

					if (this.nextInstance.properties == null) {
						continue;
					}

					try {
						var app = AppManagerImpl.this.findAppById(this.nextInstance.appId);
						final var props = this.nextInstance.properties.deepCopy();
						props.addProperty("ALIAS", this.nextInstance.alias);
						this.nextConfiguration = app.getAppConfiguration(ConfigurationTarget.VALIDATE, props, null);
					} catch (OpenemsNamedException e) {
						// move to next app
					} catch (NoSuchElementException e) {
						// app not found for instance
						// this may happen if the app id gets refactored
						// apps which app ids are not known are printed in debug log as 'UNKNOWNAPPS'
					}
				}

				return this.nextConfiguration != null;
			}
		};
	}

	@Override
	public String debugLog() {
		return this.worker.debugLog();
	}

	/**
	 * finds the app with the matching id.
	 *
	 * @param id of the app
	 * @return the found app
	 */
	protected final OpenemsApp findAppById(String id) throws NoSuchElementException {
		return this.availableApps.stream() //
				.filter(t -> t.getAppId().equals(id)) //
				.findFirst() //
				.get();
	}

	/**
	 * Finds the app instance with the matching id.
	 *
	 * @param uuid the id of the instance
	 * @return s the instance
	 * @throws NoSuchElementException if no instance is present
	 */
	protected final OpenemsAppInstance findInstanceById(UUID uuid) throws NoSuchElementException {
		return this.instantiatedApps.stream() //
				.filter(t -> t.instanceId.equals(uuid)) //
				.findFirst() //
				.get();
	}

	/**
	 * Gets all {@link AppConfiguration}s from the existing
	 * {@link OpenemsAppInstance}s.
	 *
	 * @param ignoreIds the id's of the instances that should be ignored
	 * @return the {@link AppConfiguration}s
	 */
	public final List<AppConfiguration> getOtherAppConfigurations(UUID... ignoreIds) {
		List<AppConfiguration> allOtherConfigs = new ArrayList<>(this.instantiatedApps.size());
		for (var entry : this.appConfigs(AppManagerImpl.excludingInstanceIds(ignoreIds))) {
			allOtherConfigs.add(entry.getValue());
		}
		return allOtherConfigs;
	}

	private final OpenemsAppInstance createInstanceWithFilledProperties(//
			final OpenemsApp openemsApp, //
			final OpenemsAppInstance instance //
	) {
		var properties = instance.properties;
		if (openemsApp instanceof AbstractOpenemsAppWithProps) {
			properties = ((AbstractOpenemsAppWithProps<?, ?, ?>) openemsApp).fillUpProperties(properties);
		}
		return new OpenemsAppInstance(//
				instance.appId, instance.alias, instance.instanceId, //
				properties, instance.dependencies //
		);
	}

	/**
	 * Handles {@link AddAppInstance}.
	 *
	 * @param user          the User
	 * @param request       the {@link AddAppInstance} Request
	 * @param ignoreBackend should only be used internally
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	public CompletableFuture<AddAppInstance.Response> handleAddAppInstanceRequest(User user,
			AddAppInstance.Request request, boolean ignoreBackend) throws OpenemsNamedException {
		// check if key is valid for this app
		if (!ignoreBackend && !this.backendUtil.isKeyApplicable(user, request.key, request.appId)) {
			throw new OpenemsException("Key not applicable!");
		}

		var openemsApp = this.findAppById(request.appId);
		final var response = this.lockModifyingApps(() -> {
			List<String> warnings = new ArrayList<>();
			var instance = new OpenemsAppInstance(openemsApp.getAppId(), request.alias, UUID.randomUUID(),
					request.properties, null);
			if (!ignoreBackend) {
				try {
					// try to send the backend the install request
					this.backendUtil.addInstallAppInstanceHistory(user, request.key, request.appId,
							instance.instanceId);
				} catch (OpenemsNamedException e) {
					// if timeout happens but the backend registered the app as installed it may
					// need to be synchronized again
					if (e.getMessage().contains("Read timed out")) {
						this.appSynchronizeWorker.setValidBackendResponse(false);
						this.appSynchronizeWorker.triggerNextRun();
					}
					throw e;
				}
			}

			try {
				final var installedValues = this.lastUpdate = this.useAppManagerAppHelper(appHelper -> {
					// actually install the app
					return appHelper.installApp(user, instance, openemsApp);
				});

				warnings.addAll(installedValues.warnings);
				this.instantiatedApps.removeAll(installedValues.modifiedOrCreatedApps);
				this.instantiatedApps.addAll(installedValues.modifiedOrCreatedApps);
			} catch (Throwable e) {
				// installation failed but already registered in the backend so still add the
				// instance
				warnings.add("Installation failed: " + e.getMessage());
				this.instantiatedApps.add(instance);
			}
			var instanceWithFilledProperties = this.createInstanceWithFilledProperties(openemsApp, instance);
			return CompletableFuture.completedFuture(//
					new AddAppInstance.Response(request.id, instanceWithFilledProperties, warnings));
		});
		try {
			// Update App-Manager configuration
			this.updateAppManagerConfiguration(user, this.instantiatedApps);
		} catch (OpenemsNamedException e) {
			this.appSynchronizeWorker.setValidBackendResponse(false);
			this.appSynchronizeWorker.triggerNextRun();
			throw new OpenemsException("AddAppInstance: unable to update App-Manager configuration: " + e.getMessage());
		}
		return response;
	}

	/**
	 * Handles {@link AddAppInstance}.
	 *
	 * @param user    the User
	 * @param request the {@link AddAppInstance} Request
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	public CompletableFuture<AddAppInstance.Response> handleAddAppInstanceRequest(User user,
			AddAppInstance.Request request) throws OpenemsNamedException {
		return this.handleAddAppInstanceRequest(user, request, false);
	}

	/**
	 * Handles a {@link DeleteAppInstance}.
	 *
	 * @param user    the user
	 * @param request the {@link DeleteAppInstance}
	 * @return the request id
	 * @throws OpenemsNamedException on error
	 */
	public CompletableFuture<? extends JsonrpcResponseSuccess> handleDeleteAppInstanceRequest(User user,
			DeleteAppInstance.Request request) throws OpenemsNamedException {
		final var updatedResultPair = this.lockModifyingApps(() -> {
			final OpenemsAppInstance instance;
			try {
				instance = this.findInstanceById(request.instanceId);
			} catch (NoSuchElementException e) {
				return null;
			}

			final var result = this.lastUpdate = this.useAppManagerAppHelper(appHelper -> {
				return appHelper.deleteApp(user, instance);
			});
			this.instantiatedApps.removeAll(result.deletedApps);
			// replace modified apps
			this.instantiatedApps.removeAll(result.modifiedOrCreatedApps);
			this.instantiatedApps.addAll(result.modifiedOrCreatedApps);
			return new Pair<>(result, instance);
		});

		final var updatedResult = updatedResultPair.first;
		final var removedInstance = updatedResultPair.second;

		try {
			this.updateAppManagerConfiguration(user, this.instantiatedApps);
		} catch (OpenemsNamedException e) {
			throw new OpenemsException("Unable to update App-Manager configuration for ID [" + request.instanceId
					+ "]: " + e.getMessage());
		}

		var backendDeinstallFuture = this.backendUtil.addDeinstallAppInstanceHistory(user, removedInstance.appId,
				removedInstance.instanceId);
		backendDeinstallFuture.whenComplete((r, t) -> {
			if (t == null) {
				return;
			}
			// unable to write to the backend that the app got deinstalled
			this.log.error("Unable to send deinstall app instance to backend!", t);
			this._setAppsNotSyncedWithBackend(true);
		});
		if (updatedResult == null) {
			return CompletableFuture.completedFuture(new GenericJsonrpcResponseSuccess(request.id));
		} else {
			return CompletableFuture
					.completedFuture(new DeleteAppInstance.Response(request.id, updatedResult.warnings));
		}
	}

	/**
	 * Handles {@link GetAppAssistant}.
	 *
	 * @param user    the User
	 * @param request the {@link GetAppAssistant} Request
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	private CompletableFuture<JsonrpcResponseSuccess> handleGetAppAssistantRequest(User user,
			GetAppAssistant.Request request) throws OpenemsNamedException {
		for (var app : this.availableApps) {
			if (request.appId.equals(app.getAppId())) {
				return CompletableFuture.completedFuture(
						new GetAppAssistant.Response(request.id, app.getAppAssistant(user.getLanguage())));
			}
		}
		throw new OpenemsException("App-ID [" + request.appId + "] is unknown");
	}

	/**
	 * Handles {@link GetAppDescriptor}.
	 *
	 * @param user    the User
	 * @param request the {@link GetAppDescriptor} Request
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	private CompletableFuture<JsonrpcResponseSuccess> handleGetAppDescriptorRequest(User user,
			GetAppDescriptor.Request request) throws OpenemsNamedException {
		try {
			var app = this.findAppById(request.appId);
			return CompletableFuture.completedFuture(new GetAppDescriptor.Response(request.id, app.getAppDescriptor()));
		} catch (NoSuchElementException e) {
			throw new OpenemsException("App-ID [" + request.appId + "] is unknown");
		}
	}

	/**
	 * Handles {@link GetAppInstances}.
	 *
	 * @param user    the User
	 * @param request the {@link GetAppInstances} Request
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	private CompletableFuture<JsonrpcResponseSuccess> handleGetAppInstancesRequest(User user,
			GetAppInstances.Request request) throws OpenemsNamedException {
		var instances = this.instantiatedApps.stream() //
				.filter(i -> i.appId.equals(request.appId)) //
				.map(t -> {
					final var app = this.findAppById(t.appId);
					var properties = t.properties;
					if (app instanceof AbstractOpenemsAppWithProps) {
						properties = ((AbstractOpenemsAppWithProps<?, ?, ?>) app).fillUpProperties(properties);
					}
					return new OpenemsAppInstance(t.appId, t.alias, t.instanceId, properties, t.dependencies);
				});
		return CompletableFuture.completedFuture(new GetAppInstances.Response(request.id, instances));
	}

	/**
	 * Handles a {@link GetAppRequest}.
	 *
	 * @param user    the User
	 * @param request the {@link GetAppRequest}
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	private CompletableFuture<JsonrpcResponseSuccess> handleGetAppRequest(User user, GetApp.Request request)
			throws OpenemsNamedException {
		var app = this.availableApps.stream().filter(t -> t.getAppId().equals(request.appId)).findFirst().get();
		var instances = this.instantiatedApps.stream().filter(t -> t.appId.equals(request.appId))
				.collect(Collectors.toList());
		return CompletableFuture
				.completedFuture(new GetApp.Response(request.id, app, instances, user.getLanguage(), this.validator));
	}

	/**
	 * Handles a {@link GetApps.Request}.
	 *
	 * @param user    the User
	 * @param request the {@link GetApps.Request}
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	private CompletableFuture<JsonrpcResponseSuccess> handleGetAppsRequest(User user, GetApps.Request request)
			throws OpenemsNamedException {
		return CompletableFuture.completedFuture(new GetApps.Response(request.id, this.availableApps,
				this.instantiatedApps, user.getRole(), user.getLanguage(), this.validator));
	}

	@Override
	public CompletableFuture<? extends JsonrpcResponseSuccess> handleJsonrpcRequest(User user, JsonrpcRequest request)
			throws OpenemsNamedException {
		user.assertRoleIsAtLeast("handleJsonrpcRequest", Role.OWNER);

		switch (request.getMethod()) {

		case GetApps.METHOD:
			return this.handleGetAppsRequest(user, GetApps.Request.from(request));

		case GetApp.METHOD:
			return this.handleGetAppRequest(user, GetApp.Request.from(request));

		case GetAppAssistant.METHOD:
			return this.handleGetAppAssistantRequest(user, GetAppAssistant.Request.from(request));

		case GetAppDescriptor.METHOD:
			return this.handleGetAppDescriptorRequest(user, GetAppDescriptor.Request.from(request));

		case GetAppInstances.METHOD:
			return this.handleGetAppInstancesRequest(user, GetAppInstances.Request.from(request));

		case AddAppInstance.METHOD:
			return this.handleAddAppInstanceRequest(user, AddAppInstance.Request.from(request), false);

		case UpdateAppInstance.METHOD:
			return this.handleUpdateAppInstanceRequest(user, UpdateAppInstance.Request.from(request));

		case DeleteAppInstance.METHOD:
			return this.handleDeleteAppInstanceRequest(user, DeleteAppInstance.Request.from(request));

		default:
			throw OpenemsError.JSONRPC_UNHANDLED_METHOD.exception(request.getMethod());
		}
	}

	/**
	 * Handles {@link UpdateAppInstance}.
	 *
	 * @param user    the User
	 * @param request the {@link UpdateAppInstance} Request
	 * @return the Future JSON-RPC Response
	 * @throws OpenemsNamedException on error
	 */
	public CompletableFuture<UpdateAppInstance.Response> handleUpdateAppInstanceRequest(User user,
			UpdateAppInstance.Request request) throws OpenemsNamedException {
		final var response = this.lockModifyingApps(() -> {
			final OpenemsAppInstance oldApp;
			final OpenemsApp app;

			try {
				oldApp = this.findInstanceById(request.instanceId);
				app = this.findAppById(oldApp.appId);
			} catch (NoSuchElementException e) {
				throw new OpenemsException("App-Instance-ID [" + request.instanceId + "] is unknown.");
			}
			final var updatedInstance = new OpenemsAppInstance(oldApp.appId, request.alias, oldApp.instanceId,
					request.properties, oldApp.dependencies);

			var result = this.lastUpdate = this.useAppManagerAppHelper(appHelper -> {
				return appHelper.updateApp(user, oldApp, updatedInstance, app);
			});
			// Update App-Manager configuration
			this.instantiatedApps.removeAll(result.deletedApps);
			// replace old instances with new ones
			this.instantiatedApps.removeAll(result.modifiedOrCreatedApps);
			this.instantiatedApps.addAll(result.modifiedOrCreatedApps);
			return CompletableFuture.completedFuture(//
					new UpdateAppInstance.Response(request.id, //
							this.createInstanceWithFilledProperties(app, result.rootInstance), result.warnings));
		});
		try {
			this.updateAppManagerConfiguration(user, this.instantiatedApps);
		} catch (OpenemsNamedException e) {
			throw new OpenemsException("Unable to update App-Manager configuration for ID [" + request.instanceId
					+ "]: " + e.getMessage());
		}
		return response;
	}

	/**
	 * updated the AppManager configuration with the given app instances.
	 *
	 * @param user the executing user
	 * @param apps the new apps that should be written in the configuration
	 * @throws OpenemsNamedException when the configuration can not be updated
	 */
	private void updateAppManagerConfiguration(User user, List<OpenemsAppInstance> apps) throws OpenemsNamedException {
		AppManagerImpl.sortApps(apps);
		var p = new Property("apps", getJsonAppsString(apps));
		var updateRequest = new UpdateComponentConfigRequest(SINGLETON_COMPONENT_ID, Arrays.asList(p));
		// user can be null using internal method
		if (user == null) {
			((ComponentManagerImpl) this.componentManager).handleUpdateComponentConfigRequest(user, updateRequest);
		} else {
			this.componentManager.handleJsonrpcRequest(user, updateRequest);
		}
	}

	private static void sortApps(List<OpenemsAppInstance> apps) {
		var compareTransformer = new BiFunction<Integer, Integer, Integer>() {
			@Override
			public Integer apply(Integer t, Integer u) {
				if (t == 0) {
					return 0;
				}
				return (int) (t / Math.abs(t) * Math.pow(10, u));
			}
		};
		apps.sort((o1, o2) -> {
			var value = compareTransformer.apply(o1.appId.compareTo(o2.appId), 3);
			var aliasValue = compareTransformer.apply(
					Optional.ofNullable(o1.alias).orElse("").compareTo(Optional.ofNullable(o2.alias).orElse("")), 2);
			var instanceValue = compareTransformer.apply(o1.instanceId.compareTo(o2.instanceId), 1);
			return value + aliasValue + instanceValue;
		});
	}

	private <T> T useAppManagerAppHelper(ThrowingFunction<AppManagerAppHelper, T, OpenemsNamedException> consumer)
			throws OpenemsNamedException {
		final var service = this.csoAppManagerAppHelper.getService();
		try {
			return consumer.apply(service);
		} finally {
			if (service != null) {
				this.csoAppManagerAppHelper.ungetService(service);
			}
		}
	}

	private <T> T lockModifyingApps(ThrowingSupplier<T, OpenemsNamedException> supplier) throws OpenemsNamedException {
		try {
			this.mutexForUpdatingConfig.await();
			this.aquiredMutex = true;
		} catch (InterruptedException e) {
			throw new OpenemsException(e);
		}
		synchronized (this.appSynchronizeWorker.getSynchronizationLock()) {
			synchronized (this.instantiatedApps) {
				return supplier.get();
			}
		}
	}

	private final class Pair<F, S> {
		public final F first;
		public final S second;

		public Pair(F first, S second) {
			super();
			this.first = first;
			this.second = second;
		}

	}

}
