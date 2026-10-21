package lodgebilling.dao;

import lodgebilling.model.RateCard;
import lodgebilling.model.RoomType;

import java.math.BigDecimal;
import java.util.Optional;

/** Room rates and simple key/value settings such as the VAT %. */
public interface SettingsDao {

    String VAT_PERCENT = "vat_percent";

    Optional<String> get(String key);

    /** Saves a setting, replacing any earlier value. */
    void put(String key, String value);

    /** The nightly rates and the VAT %, using defaultVat if none has been saved. */
    RateCard loadRateCard(BigDecimal defaultVat);

    void saveRate(RoomType type, BigDecimal rate);
}
