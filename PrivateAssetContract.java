import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeStub;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

@Contract(name = "PrivateAssetContract")
@Default
public class PrivateAssetContract implements ContractInterface {

    private static final Logger logger = Logger.getLogger(PrivateAssetContract.class.getName());
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final String PRIVATE_DATA_COLLECTION = "assetPrivateDetails";
    private static final int MAX_CACHE_SIZE = 1000;

    @Transaction
    public void initLedger(Context ctx) {
        logger.info("Contract initialized");
    }

    @Transaction
    public void createAsset(Context ctx, String assetId, String owner,
            String assetType, String description, double value) {

        validateInput(assetId, "Asset ID cannot be null or empty");
        validateInput(owner, "Owner cannot be null or empty");
        validateInput(assetType, "Asset type cannot be null or empty");

        ChaincodeStub stub = ctx.getStub();

        String existingAsset = getFromCacheOrLedger(assetId, stub);
        if (existingAsset != null && !existingAsset.isEmpty()) {
            throw new AssetAlreadyExistsException("Asset " + assetId + " already exists");
        }

        Asset asset = new Asset(assetId, owner, assetType, description, value);
        String assetJSON = asset.toJSON();

        try {
            stub.putStringState(assetId, assetJSON);
            putInCache(assetId, assetJSON);
            logger.info("Asset " + assetId + " created by " + owner);
        } catch (Exception e) {
            throw new AssetOperationException("Error creating asset: " + e.getMessage());
        }
    }

    @Transaction
    public void createPrivateAssetDetails(Context ctx, String assetId) {
        validateInput(assetId, "Asset ID cannot be null or empty");

        ChaincodeStub stub = ctx.getStub();

        try {
            Map<String, byte[]> transientMap = stub.getTransient();

            if (!transientMap.containsKey("asset_properties")) {
                throw new PrivateDataException("Private data not found in transient map");
            }

            String privateDetails = new String(transientMap.get("asset_properties"));
            validateInput(privateDetails, "Private details cannot be empty");

            stub.putPrivateData(PRIVATE_DATA_COLLECTION, assetId, privateDetails);

            logger.info("Private data for asset " + assetId + " stored");
        } catch (Exception e) {
            throw new PrivateDataException("Error storing private data: " + e.getMessage());
        }
    }

    @Transaction
    public String queryAsset(Context ctx, String assetId) {
        validateInput(assetId, "Asset ID cannot be null or empty");

        ChaincodeStub stub = ctx.getStub();
        String asset = getFromCacheOrLedger(assetId, stub);

        if (asset == null || asset.isEmpty()) {
            throw new AssetNotFoundException("Asset " + assetId + " not found");
        }

        return asset;
    }

    @Transaction
    public String queryPrivateAssetDetails(Context ctx, String assetId) {
        validateInput(assetId, "Asset ID cannot be null or empty");

        ChaincodeStub stub = ctx.getStub();

        try {
            String mspId = ctx.getClientIdentity().getMSPID();
            logger.info("Private data access attempt by: " + mspId);

            byte[] privateDataBytes = stub.getPrivateData(PRIVATE_DATA_COLLECTION, assetId);

            if (privateDataBytes == null) {
                throw new PrivateDataException("No private data for asset " + assetId);
            }

            return new String(privateDataBytes);
        } catch (Exception e) {
            throw new PrivateDataException("Error reading private data: " + e.getMessage());
        }
    }

    @Transaction
    public void transferAsset(Context ctx, String assetId, String newOwner) {
        validateInput(assetId, "Asset ID cannot be null or empty");
        validateInput(newOwner, "New owner cannot be null or empty");

        ChaincodeStub stub = ctx.getStub();

        try {
            String assetJSON = getFromCacheOrLedger(assetId, stub);
            if (assetJSON == null || assetJSON.isEmpty()) {
                throw new AssetNotFoundException("Asset " + assetId + " does not exist");
            }

            Asset asset = Asset.fromJSON(assetJSON);
            String oldOwner = asset.getOwner();
            asset.setOwner(newOwner);

            String updatedAssetJSON = asset.toJSON();
            stub.putStringState(assetId, updatedAssetJSON);

            putInCache(assetId, updatedAssetJSON);
            invalidateCache(oldOwner + "_assets");

            logger.info("Asset " + assetId + " transferred from " + oldOwner + " to " + newOwner);
        } catch (AssetNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new AssetOperationException("Error transferring asset: " + e.getMessage());
        }
    }

    @Transaction
    public String getAssetHistory(Context ctx, String assetId) {
        validateInput(assetId, "Asset ID cannot be null or empty");

        ChaincodeStub stub = ctx.getStub();

        try {
            return stub.getHistoryForKey(assetId).toString();
        } catch (Exception e) {
            throw new AssetOperationException("Error getting asset history: " + e.getMessage());
        }
    }

    private String getFromCacheOrLedger(String key, ChaincodeStub stub) {
        if (cache.containsKey(key)) {
            logger.info("Cache hit for key: " + key);
            return cache.get(key);
        } else {
            logger.info("Cache miss for key: " + key);
            String value = stub.getStringState(key);
            if (value != null && !value.isEmpty()) {
                putInCache(key, value);
            }
            return value;
        }
    }

    private void putInCache(String key, String value) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            String firstKey = cache.keySet().iterator().next();
            cache.remove(firstKey);
            logger.info("Cache evicted key: " + firstKey);
        }
        cache.put(key, value);
    }

    private void invalidateCache(String key) {
        cache.remove(key);
        logger.info("Cache invalidated for key: " + key);
    }

    private void validateInput(String input, String errorMessage) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    public static class AssetNotFoundException extends RuntimeException {
        public AssetNotFoundException(String message) {
            super(message);
        }
    }

    public static class AssetAlreadyExistsException extends RuntimeException {
        public AssetAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class PrivateDataException extends RuntimeException {
        public PrivateDataException(String message) {
            super(message);
        }
    }

    public static class AssetOperationException extends RuntimeException {
        public AssetOperationException(String message) {
            super(message);
        }
    }

    public static class Asset {
        private String assetId;
        private String owner;
        private String assetType;
        private String description;
        private double value;

        public Asset(String assetId, String owner, String assetType, String description, double value) {
            this.assetId = sanitizeString(assetId);
            this.owner = sanitizeString(owner);
            this.assetType = sanitizeString(assetType);
            this.description = sanitizeString(description);
            this.value = value;
        }

        private String sanitizeString(String input) {
            if (input == null)
                return "";
            return input.replace("\"", "\\\"").replace("\n", "").replace("\r", "").trim();
        }

        public String getAssetId() {
            return assetId;
        }

        public void setAssetId(String assetId) {
            this.assetId = sanitizeString(assetId);
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = sanitizeString(owner);
        }

        public String getAssetType() {
            return assetType;
        }

        public void setAssetType(String assetType) {
            this.assetType = sanitizeString(assetType);
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = sanitizeString(description);
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }

        public String toJSON() {
            return String.format(
                    "{\"assetId\":\"%s\",\"owner\":\"%s\",\"assetType\":\"%s\",\"description\":\"%s\",\"value\":%.2f}",
                    assetId, owner, assetType, description, value);
        }

        public static Asset fromJSON(String json) {
            if (json == null || json.trim().isEmpty()) {
                throw new IllegalArgumentException("JSON string cannot be null or empty");
            }

            try {
                String assetId = extractJsonValue(json, "assetId");
                String owner = extractJsonValue(json, "owner");
                String assetType = extractJsonValue(json, "assetType");
                String description = extractJsonValue(json, "description");
                String valueStr = extractJsonValue(json, "value");

                if (assetId.isEmpty() || owner.isEmpty() || assetType.isEmpty() || valueStr.isEmpty()) {
                    throw new IllegalArgumentException("Missing required fields in JSON");
                }

                double value = Double.parseDouble(valueStr);
                return new Asset(assetId, owner, assetType, description, value);

            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid value format in JSON: " + e.getMessage());
            } catch (Exception e) {
                throw new IllegalArgumentException("Error parsing JSON: " + e.getMessage());
            }
        }

        private static String extractJsonValue(String json, String key) {

            Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"|\"" + key + "\"\\s*:\\s*([^,}]*)");
            Matcher matcher = pattern.matcher(json);

            if (matcher.find()) {
                String quotedValue = matcher.group(1);
                String unquotedValue = matcher.group(2);

                if (quotedValue != null) {
                    return quotedValue.replace("\\\"", "\""); // Unescape quotes
                } else if (unquotedValue != null) {
                    return unquotedValue.trim();
                }
            }

            return "";
        }
    }
}