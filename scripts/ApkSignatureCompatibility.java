import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares two already-verified APK signing identities using Android's
 * installed-data or signature-permission key-rotation semantics. No
 * certificate material is printed; callers receive only an exit status.
 */
public final class ApkSignatureCompatibility {
    private ApkSignatureCompatibility() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println(
                    "Expected a compatibility mode, target API, release APK, and installed APK.");
            System.exit(2);
        }

        int targetApi = Integer.parseInt(args[1]);
        if (targetApi < 1) {
            throw new IllegalArgumentException("Target API must be positive.");
        }
        ApkVerifier.Result release = verify(new File(args[2]), targetApi);
        ApkVerifier.Result anchor = verify(new File(args[3]), targetApi);
        boolean compatible = switch (args[0]) {
            case "installed-data" -> isInstalledDataCompatible(release, anchor);
            case "signature-permission" -> isSignaturePermissionCompatible(release, anchor);
            default -> throw new IllegalArgumentException("Unknown APK compatibility mode.");
        };
        if (!compatible) {
            System.err.println("APK signing identities are not compatible for the requested capability.");
            System.exit(1);
        }
    }

    private static ApkVerifier.Result verify(File apk, int targetApi) throws Exception {
        ApkVerifier.Result result =
                new ApkVerifier.Builder(apk)
                        .setMaxCheckedPlatformVersion(targetApi)
                        .build()
                        .verify();
        if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
            throw new IllegalArgumentException("An APK trust anchor failed signature verification.");
        }
        return result;
    }

    private static boolean isSignaturePermissionCompatible(
            ApkVerifier.Result permissionOwner,
            ApkVerifier.Result permissionConsumer) {
        List<X509Certificate> ownerCurrent = permissionOwner.getSignerCertificates();
        List<X509Certificate> consumerCurrent = permissionConsumer.getSignerCertificates();
        if (new HashSet<>(ownerCurrent).equals(new HashSet<>(consumerCurrent))) {
            return true;
        }

        // Android does not support certificate rotation for multiple-signer packages.
        if (ownerCurrent.size() != 1 || consumerCurrent.size() != 1) {
            return false;
        }

        SigningCertificateLineage consumerLineage = permissionConsumer.getSigningCertificateLineage();
        Set<X509Certificate> consumerHistory = new HashSet<>(consumerCurrent);
        if (consumerLineage != null) {
            consumerHistory.addAll(consumerLineage.getCertificatesInLineage());
        }

        // A consumer rotated from the permission owner's current signer remains trusted.
        if (consumerHistory.contains(ownerCurrent.get(0))) {
            return true;
        }

        // A permission owner may keep trusting an older consumer signer only when its
        // proof-of-rotation explicitly preserves the PERMISSION capability for that signer.
        SigningCertificateLineage ownerLineage = permissionOwner.getSigningCertificateLineage();
        if (ownerLineage == null) {
            return false;
        }
        for (X509Certificate certificate : ownerLineage.getCertificatesInLineage()) {
            if (consumerHistory.contains(certificate)
                    && ownerLineage.getSignerCapabilities(certificate).hasPermission()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInstalledDataCompatible(
            ApkVerifier.Result release,
            ApkVerifier.Result installed) {
        List<X509Certificate> releaseCurrent = release.getSignerCertificates();
        List<X509Certificate> installedCurrent = installed.getSignerCertificates();
        if (new HashSet<>(releaseCurrent).equals(new HashSet<>(installedCurrent))) {
            return true;
        }
        if (releaseCurrent.size() != 1 || installedCurrent.size() != 1) {
            return false;
        }

        SigningCertificateLineage releaseLineage = release.getSigningCertificateLineage();
        if (releaseLineage != null
                && releaseLineage.isCertificateInLineage(installedCurrent.get(0))
                && releaseLineage.getSignerCapabilities(installedCurrent.get(0)).hasInstalledData()) {
            return true;
        }

        SigningCertificateLineage installedLineage = installed.getSigningCertificateLineage();
        return installedLineage != null
                && installedLineage.isCertificateInLineage(releaseCurrent.get(0))
                && installedLineage.getSignerCapabilities(releaseCurrent.get(0)).hasRollback();
    }
}
