package io.openems.edge.kaco.blueplanet.hybrid10.core;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.jmdns.ServiceInfo;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ed.data.BatteryData;
import com.ed.data.EnergyMeter;
import com.ed.data.InverterData;
import com.ed.data.Settings;
import com.ed.data.Status;
import com.ed.data.SystemInfo;
import com.ed.data.VectisData;
import com.ed.edcom.Client;
import com.ed.edcom.Discovery;
import com.ed.edcom.Util;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;

@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "Kaco.BlueplanetHybrid10.Core", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				EventConstants.EVENT_TOPIC + "=" + EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		})
public class BpCoreImpl extends AbstractOpenemsComponent implements BpCore, OpenemsComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(BpCoreImpl.class);
	private final ScheduledExecutorService configExecutor = Executors.newSingleThreadScheduledExecutor();

	private ScheduledFuture<?> configFuture = null;
	private Client client = null;
	private BatteryData battery = null;
	private InverterData inverter = null;
	private Status status = null;
	private Settings settings = null;
	private VectisData vectis = null;
	private EnergyMeter energy = null;
	private SystemInfo systemInfo = null;
	private String userkey;
	private String serialNumber;

	public BpCoreImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				BpCore.ChannelId.values() //
		);
	}

	@Activate
	void activate(ComponentContext context, Config config) throws UnknownHostException, SocketException {
		super.activate(context, config.id(), config.alias(), config.enabled());

		// System.setProperty("java.net.preferIPv4Stack" , "true");

		/*
		 * Async initialize library and connection
		 */
		this.userkey = config.userkey();
		this.serialNumber = config.serialnumber();

		final InetAddress inverterAddress;
		if (config.ip() == null) {
			inverterAddress = null;
		} else {
			inverterAddress = InetAddress.getByName(config.ip());
		}
		Runnable initializeLibrary = () -> {
			while (true) {
				try {
					this.initialize(inverterAddress);
					break; // stop forever loop
				} catch (Exception e) {
					this.logError(this.log, e.getMessage());
					this._setCommunicationFailed(true);
					e.printStackTrace();
				}
				try {
					Thread.sleep(2000); // wait for next try
				} catch (InterruptedException e) {
					this.logError(this.log, e.getMessage());
				}
			}
			this._setCommunicationFailed(false);
		};
		this.configFuture = configExecutor.schedule(initializeLibrary, 0, TimeUnit.SECONDS);
	}

	private void initialize(InetAddress inverterAddress) throws Exception {
		Util.getInstance().setUserName( //
				"K+JxgBxJPPzGuCZjznH35ggVlzY8NVV8Y9vZ8nU9k3RTiQBJxBcY8F0Umv3H2tCfCTpQTcZBDIZFd52Y54WvBojYm"
						+ "BxD84MoHXexNpr074zyhahFwppN+fZPXMIGaYTng0Mvv1XdYKdCMhh6xElc7eM3Q9e9JOWAbpD3eTX8L/yOVT8sVv"
						+ "n0q6oL4m2+pASNLHBFAVfRFjtNYVCIsjpnEEbsNN7OwO6IdokBV1qbbXbaWWljco/Sz3zD/l35atntDHwkyTG2Tpv"
						+ "Z1HWGBZVt39z17LxK8baCVIRw02/P6QjCStbnCPaVEEZquW/YpGrHRg5v8E3wlNx8U+Oy/TyIsA==");

		if (inverterAddress != null) {
			/*
			 * IP address was set. No need for discovery.
			 */

		} else {
			/*
			 * No IP address was set. Use discovery.
			 */
			Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
			for (NetworkInterface iface : Collections.list(ifaces)) {
				// Initialize discovery

				InetAddress localAddress = null;
				Enumeration<InetAddress> localAddresses = iface.getInetAddresses();
				while (localAddresses.hasMoreElements()) {
					localAddress = localAddresses.nextElement();
					this.logInfo(this.log, "Edcom start discovery on [" + iface.getDisplayName() + ", "
							+ localAddress.getHostAddress() + "]");
					Discovery discovery = Discovery.getInstance(localAddress);

					// Start discovery
					ServiceInfo inverter = null;
					if (this.serialNumber != null) {
						// Search by serialnumber if it was configured
						inverter = discovery.getBySerialNumber(this.serialNumber);
					} else {
						// Otherwise discover all and take first discovered inverter
						ServiceInfo[] inverterList = discovery.refreshInverterList();
						if (inverterList.length > 0) {
							inverter = inverterList[0];
						}
					}

					// Finalize discovery
					try {
						discovery.close();
					} catch (IOException e) {
						this.logWarn(this.log, e.getMessage());
					}

					// Get inverterAddress
					if (inverter != null) {
						InetAddress[] addresses = inverter.getInetAddresses();
						if (addresses.length > 0) {
							inverterAddress = addresses[0]; // use the first address
							this.logInfo(this.log, "Found inverter: " + inverterAddress.toString());
							break; // quit searching
						}
					}
				}

				if (inverterAddress != null) {
					break;
				}
			}
		}

		if (inverterAddress != null) {
			this.initClient(inverterAddress);
		}
	}

	@Deactivate
	protected void deactivate() {
		if (this.client != null) {
			try {
				this.client.close();
			} catch (IOException e) {
				this.logError(this.log, e.getMessage());
				e.printStackTrace();
			}
		}

		// Shutdown executor
		if (this.configExecutor != null) {
			this.configFuture.cancel(true);
		}
		try {
			configExecutor.shutdown();
			configExecutor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			this.logWarn(this.log, "tasks interrupted");
		} finally {
			if (!configExecutor.isTerminated()) {
				this.logWarn(this.log, "cancel non-finished tasks");
			}
			configExecutor.shutdownNow();
		}
		super.deactivate();
	}

	public BatteryData getBatteryData() {
		if (this.battery != null && this.battery.dataReady()) {
			this.battery.refresh();
		}
		return this.battery;
	}

	@Override
	public InverterData getInverterData() {
		if (this.inverter != null && this.inverter.dataReady()) {
			this.inverter.refresh();
		}
		return this.inverter;
	}

	@Override
	public Status getStatusData() {
		if (this.status != null && this.status.dataReady()) {
			this.status.refresh();
		}
		return this.status;
	}

	@Override
	public boolean isConnected() {
		return this.client != null && this.client.isConnected();
	}

	@Override
	public Settings getSettings() {
		if (this.settings != null && this.settings.dataReady()) {
			this.settings.refresh();
		}
		return this.settings;
	}

	@Override
	public VectisData getVectis() {
		if (this.vectis != null && this.vectis.dataReady()) {
			this.vectis.refresh();
		}
		return this.vectis;
	}

	@Override
	public EnergyMeter getEnergyMeter() {
		if (this.energy != null && this.energy.dataReady()) {
			this.energy.refresh();
		}
		return this.energy;
	}

	@Override
	public SystemInfo getSystemInfo() {
		if (this.systemInfo != null && this.systemInfo.dataReady()) {
			this.systemInfo.refresh();
		}
		return this.systemInfo;
	}

	/**
	 * Gets a local IP address which is able to access the given remote IP.
	 * 
	 * @param inetAddress
	 * @return a local IP address or null if no match was found
	 */
	public static InetAddress getMatchingLocalInetAddress(InetAddress inetAddress) {
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(inetAddress, 9760);
			InetAddress localAddress = socket.getLocalAddress();

			if (localAddress.isAnyLocalAddress()) {
				return null;
			} else {
				return localAddress;
			}
		} catch (SocketException e) {
			return null;
		}
	}

	private void initClient(InetAddress inverterAddress) throws Exception {
		// Initialize the Client
		InetAddress localAddress = BpCoreImpl.getMatchingLocalInetAddress(inverterAddress);
		this.client = new Client(inverterAddress, localAddress, 1);

		// Initialize all Data classes
		this.client.setUserKey(this.userkey);

		this.battery = new BatteryData();
		this.battery.registerData(client);
		this.inverter = new InverterData();
		this.inverter.registerData(client);
		this.status = new Status();
		this.status.registerData(client);
		this.settings = new Settings();
		this.settings.registerData(client);
		this.energy = new EnergyMeter();
		this.energy.registerData(client);
		this.vectis = new VectisData();
		this.vectis.registerData(client);
		this.systemInfo = new SystemInfo();
		this.systemInfo.registerData(client);

		this.client.start();

		// Get User-Status
		int userStatus;
		do {
			userStatus = this.client.getUserStatus();

			// Set USER_ACCESS_DENIED Fault-Channel
			this._setUserAccessDenied(userStatus == 0);

			switch (userStatus) {
			case -1: // not read
				Thread.sleep(1000); // try again after 1 second
				break;
			case 0: // access denied
				this.logWarn(this.log, "User Status: Access denied");
				break;
			case 1: // no password required
				this.logInfo(this.log, "User Status: No password required");
				break;
			case 2: // password accepted
				this.logInfo(this.log, "User Status: Password accepted");
				break;
			case 3: // energy depot
				this.logInfo(this.log, "User Status: EnergyDepot");
				break;
			}

		} while (userStatus == -1 /* not read */);
	}

	@Override
	public void handleEvent(Event event) {
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE:
			this.updateChannels();
			break;
		}
	}

	private void updateChannels() {
		String serialNumber = null;
		Float versionCom = null;

		if (this.isConnected()) {
			SystemInfo systemInfo = this.getSystemInfo();
			if (systemInfo != null) {
				serialNumber = systemInfo.getSerialNumber();
				if (serialNumber != null) {
					serialNumber = serialNumber.strip();
				}
				try {
					versionCom = Float.parseFloat(systemInfo.getComVersion());
				} catch (NumberFormatException e) {
					this.logWarn(this.log, "Unable to parse Com-Version from [" + systemInfo.getComVersion() + "]");
				}
			}
		}

		this._setSerialnumber(serialNumber);
		this._setVersionCom(versionCom);
	}

}
