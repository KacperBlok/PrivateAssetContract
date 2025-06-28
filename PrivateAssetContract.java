import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.contract.ContractInterface;
import org.hyperledger.fabric.contract.annotation.Contract;
import org.hyperledger.fabric.contract.annotation.Default;
import org.hyperledger.fabric.contract.annotation.Transaction;
import org.hyperledger.fabric.shim.ChaincodeStub;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@Contract(name = "PrivateAssetContract")
@Default
public class PrivateAssetContract implements ContractInterface {

    private static final Logger logger = Logger.getLogger(PrivateAssetContract.class.getName());
    private final Map<String, String> cache = new HashMap<>();
    private static final String PRIVATE_DATA_COLLECTION = "assetPrivateDetails";

    @Transaction
    public void initLedger(Context ctx) {
        logger.info("Contract initialized");
    }

    @Transaction
    public void createAsset(Context ctx, String assetId, String owner,
            String assetType, String description, double value) {

        ChaincodeStub stub = ctx.getStub();

        String existingAsset = getFromCacheOrLedger(assetId, stub);
        if (existingAsset != null && !existingAsset.isEmpty()) {
            throw new RuntimeException("Asset " + assetId + " already exists");
        }

        Asset asset = new Asset(assetId, owner, assetType, description, value);
        String assetJSON = asset.toJSON();

        try {
            stub.putStringState(assetId, assetJSON);
            cache.put(assetId, assetJSON);
            logger.info("Asset " + assetId + " created by " + owner);
        } catch (Exception e) {
            throw new RuntimeException("Error creating asset: " + e.getMessage());
        }
    }

    @Transaction
    public void createPrivateAssetDetails(Context ctx, String assetId) {
        ChaincodeStub stub = ctx.getStub();

        try {
            Map<String, byte[]> transientMap = stub.getTransient();

            if (!transientMap.containsKey("asset_properties")) {
                throw new RuntimeException("Private data not found in transient map");
            }

            String privateDetails = new String(transientMap.get("asset_properties"));
            stub.putPrivateData(PRIVATE_DATA_COLLECTION, assetId, privateDetails);

            logger.info("Private data for asset " + assetId + " stored");
        } catch (Exception e) {
            throw new RuntimeException("Error storing private data: " + e.getMessage());
        }
    }

    @Transaction
    public String queryAsset(Context ctx, String assetId) {
        ChaincodeStub stub = ctx.getStub();
        String asset = getFromCacheOrLedger(assetId, stub);

        if (asset == null || asset.isEmpty()) {
            throw new RuntimeException("Asset " + assetId + " not found");
        }

        return asset;
    }

    @Transaction
    public String queryPrivateAssetDetails(Context ctx, String assetId) {
        ChaincodeStub stub = ctx.getStub();

        try {
            String mspId = ctx.getClientIdentity().getMSPID();
            logger.info("Private data access attempt by: " + mspId);

            byte[] privateDataBytes = stub.getPrivateData(PRIVATE_DATA_COLLECTION, assetId);

            if (privateDataBytes == null) {
                throw new RuntimeException("No private data for asset " + assetId);
            }

            return new String(privateDataBytes);
        } catch (Exception e) {
            throw new RuntimeException("Error reading private data: " + e.getMessage());
        }
    }

    @Transaction
    public void transferAsset(Context ctx, String assetId, String newOwner) {
        ChaincodeStub stub = ctx.getStub();

        try {
            String assetJSON = getFromCacheOrLedger(assetId, stub);
            if (assetJSON == null || assetJSON.isEmpty()) {
                throw new RuntimeException("Asset " + assetId + " does not exist");
            }

            Asset asset = Asset.fromJSON(assetJSON);
            String oldOwner = asset.getOwner();
            asset.setOwner(newOwner);

            String updatedAssetJSON = asset.toJSON();
            stub.putStringState(assetId, updatedAssetJSON);

            cache.put(assetId, updatedAssetJSON);
            invalidateCache(oldOwner + "_assets");

            logger.info("Asset " + assetId + " transferred from " + oldOwner + " to " + newOwner);
        } catch (Exception e) {
            throw new RuntimeException("Error transferring asset: " + e.getMessage());
        }
    }

    @Transaction
    public String getAssetHistory(Context ctx, String assetId) {
        ChaincodeStub stub = ctx.getStub();

        try {
            return stub.getHistoryForKey(assetId).toString();
        } catch (Exception e) {
            throw new RuntimeException("Error getting asset history: " + e.getMessage());
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
                cache.put(key, value);
            }
            return value;
        }
    }

    private void invalidateCache(String key) {
        cache.remove(key);
        logger.info("Cache invalidated for key: " + key);
    }

    public static class Asset {
        private String assetId;
        private String owner;
        private String assetType;
        private String description;
        private double value;

        public Asset(String assetId, String owner, String assetType, String description, double value) {
            this.assetId = assetId;
            this.owner = owner;
            this.assetType = assetType;
            this.description = description;
            this.value = value;
        }

        public String getAssetId() {
            return assetId;
        }

        public void setAssetId(String assetId) {
            this.assetId = assetId;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getAssetType() {
            return assetType;
        }

        public void setAssetType(String assetType) {
            this.assetType = assetType;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
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
            String[] parts = json.replace("{", "").replace("}", "").replace("\"", "").split(",");
            String assetId = parts[0].split(":")[1];
            String owner = parts[1].split(":")[1];
            String assetType = parts[2].split(":")[1];
            String description = parts[3].split(":")[1];
            double value = Double.parseDouble(parts[4].split(":")[1]);

            return new Asset(assetId, owner, assetType, description, value);
        }
    }
}