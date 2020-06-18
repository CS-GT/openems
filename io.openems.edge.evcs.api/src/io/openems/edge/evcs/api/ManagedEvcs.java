package io.openems.edge.evcs.api;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanReadChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StringWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusType;

@ProviderType
public interface ManagedEvcs extends Evcs {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * Sets the charge power limit of the EVCS in [W].
		 * 
		 * <p>
		 * Actual charge power depends on
		 * <ul>
		 * <li>whether the electric vehicle is connected at all and ready for charging
		 * <li>hardware limit of the charging station
		 * <li>limit of electric vehicle
		 * <li>limit of power line
		 * <li>...
		 * </ul>
		 * 
		 * <p>
		 * Function
		 * <ul>
		 * <li>Write Value should be sent to the EVCS and cleared afterwards
		 * <li>Read value should contain the currently valid loading target that was
		 * sent
		 * </ul>
		 * 
		 * <ul>
		 * <li>Interface: ManagedEvcs
		 * <li>Writable
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		SET_CHARGE_POWER_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)),

		/**
		 * Is true if the EVCS is in a EVCS-Cluster.
		 * 
		 * <ul>
		 * <li>Interface: ManagedEvcs
		 * <li>Readable
		 * <li>Type: Boolean
		 * </ul>
		 */
		IS_CLUSTERED(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_ONLY)), //

		/**
		 * Sets a Text that is shown on the display of the EVCS.
		 * 
		 * <p>
		 * Be aware that the EVCS might not have a display or the text might be
		 * restricted.
		 * 
		 * <ul>
		 * <li>Interface: ManagedEvcs
		 * <li>Writable
		 * <li>Type: String
		 * </ul>
		 */
		SET_DISPLAY_TEXT(Doc.of(OpenemsType.STRING) //
				.accessMode(AccessMode.READ_WRITE)),

		/**
		 * Sets a request for a charge power. The limit is not directly activated by
		 * this call.
		 * 
		 * <ul>
		 * <li>Interface: ManagedEvcs
		 * <li>Writable
		 * <li>Type: Integer
		 * <li>Unit: W
		 * </ul>
		 */
		SET_CHARGE_POWER_REQUEST(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_WRITE)),

		/**
		 * Sets the energy limit for the current or next session in [Wh].
		 * 
		 * <ul>
		 * <li>Interface: ManagedEvcs
		 * <li>Writable
		 * <li>Type: Integer
		 * <li>Unit: Wh
		 * </ul>
		 */
		SET_ENERGY_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT_HOURS) //
				.accessMode(AccessMode.READ_WRITE));

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_CHARGE_POWER_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetChargePowerLimitChannel() {
		return this.channel(ChannelId.SET_CHARGE_POWER_LIMIT);
	}

	/**
	 * Gets the set charge power limit of the EVCS in [W]. See
	 * {@link ChannelId#SET_CHARGE_POWER_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSetChargePowerLimit() {
		return this.getSetChargePowerLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SET_CHARGE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetChargePowerLimit(Integer value) {
		this.getSetChargePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SET_CHARGE_POWER_LIMIT} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetChargePowerLimit(int value) {
		this.getSetChargePowerLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the charge power limit of the EVCS in [W]. See
	 * {@link ChannelId#SET_CHARGE_POWER_LIMIT}.
	 * 
	 * @param value the next write value
	 * @throws OpenemsNamedException on error
	 */
	public default void setChargePowerLimit(Integer value) throws OpenemsNamedException {
		this.getSetChargePowerLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#IS_CLUSTERED}.
	 *
	 * @return the Channel
	 */
	public default BooleanReadChannel getIsClusteredChannel() {
		return this.channel(ChannelId.IS_CLUSTERED);
	}

	/**
	 * Gets the Is true if the EVCS is in a EVCS-Cluster. See
	 * {@link ChannelId#IS_CLUSTERED}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Boolean> getIsClustered() {
		return this.getIsClusteredChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#IS_CLUSTERED}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setIsClustered(boolean value) {
		this.getIsClusteredChannel().setNextValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_DISPLAY_TEXT}.
	 *
	 * @return the Channel
	 */
	public default StringWriteChannel getSetDisplayTextChannel() {
		return this.channel(ChannelId.SET_DISPLAY_TEXT);
	}

	/**
	 * Gets the Text that is shown on the display of the EVCS. See
	 * {@link ChannelId#SET_DISPLAY_TEXT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<String> getSetDisplayText() {
		return this.getSetDisplayTextChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#SET_DISPLAY_TEXT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetDisplayText(String value) {
		this.getSetDisplayTextChannel().setNextValue(value);
	}

	/**
	 * Sets a Text that is shown on the display of the EVCS. See
	 * {@link ChannelId#SET_DISPLAY_TEXT}.
	 * 
	 * @param value the next write value
	 * @throws OpenemsNamedException on error
	 */
	public default void setDisplayText(String value) throws OpenemsNamedException {
		this.getSetDisplayTextChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_CHARGE_POWER_REQUEST}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetChargePowerRequestChannel() {
		return this.channel(ChannelId.SET_CHARGE_POWER_REQUEST);
	}

	/**
	 * Gets the request for a charge power in [W]. The limit is not directly
	 * activated by this call.. See {@link ChannelId#SET_CHARGE_POWER_REQUEST}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSetChargePowerRequest() {
		return this.getSetChargePowerRequestChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SET_CHARGE_POWER_REQUEST} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetChargePowerRequest(Integer value) {
		this.getSetChargePowerRequestChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on
	 * {@link ChannelId#SET_CHARGE_POWER_REQUEST} Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetChargePowerRequest(int value) {
		this.getSetChargePowerRequestChannel().setNextValue(value);
	}

	/**
	 * Sets the request for a charge power in [W]. The limit is not directly
	 * activated by this call. See {@link ChannelId#SET_CHARGE_POWER_REQUEST}.
	 * 
	 * @param value the next write value
	 * @throws OpenemsNamedException on error
	 */
	public default void setChargePowerRequest(Integer value) throws OpenemsNamedException {
		this.getSetChargePowerRequestChannel().setNextWriteValue(value);
	}

	/**
	 * Gets the Channel for {@link ChannelId#SET_ENERGY_LIMIT}.
	 *
	 * @return the Channel
	 */
	public default IntegerWriteChannel getSetEnergyLimitChannel() {
		return this.channel(ChannelId.SET_ENERGY_LIMIT);
	}

	/**
	 * Gets the energy limit for the current or next session in [Wh].. See
	 * {@link ChannelId#SET_ENERGY_LIMIT}.
	 *
	 * @return the Channel {@link Value}
	 */
	public default Value<Integer> getSetEnergyLimit() {
		return this.getSetEnergyLimitChannel().value();
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#SET_ENERGY_LIMIT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetEnergyLimit(Integer value) {
		this.getSetEnergyLimitChannel().setNextValue(value);
	}

	/**
	 * Internal method to set the 'nextValue' on {@link ChannelId#SET_ENERGY_LIMIT}
	 * Channel.
	 *
	 * @param value the next value
	 */
	public default void _setSetEnergyLimit(int value) {
		this.getSetEnergyLimitChannel().setNextValue(value);
	}

	/**
	 * Sets the energy limit for the current or next session in [Wh]. See
	 * {@link ChannelId#SET_ENERGY_LIMIT}.
	 * 
	 * @param value the next write value
	 * @throws OpenemsNamedException on error
	 */
	public default void setEnergyLimit(Integer value) throws OpenemsNamedException {
		this.getSetEnergyLimitChannel().setNextWriteValue(value);
	}

	/**
	 * Returns the modbus table for this nature.
	 * 
	 * @param accessMode accessMode
	 * @return nature table
	 */
	public static ModbusSlaveNatureTable getModbusSlaveNatureTable(AccessMode accessMode) {
		return ModbusSlaveNatureTable.of(ManagedEvcs.class, accessMode, 100) //
				.channel(0, ChannelId.SET_CHARGE_POWER_LIMIT, ModbusType.UINT16) //
				.channel(1, ChannelId.SET_DISPLAY_TEXT, ModbusType.STRING16) //
				.channel(17, ChannelId.SET_ENERGY_LIMIT, ModbusType.UINT16) //
				.build();
	}
}
