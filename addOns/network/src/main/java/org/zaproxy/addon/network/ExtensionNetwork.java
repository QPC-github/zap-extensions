/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2021 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.addon.network;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.SSLConnector;
import org.parosproxy.paros.security.CertData;
import org.parosproxy.paros.security.MissingRootCertificateException;
import org.parosproxy.paros.security.SslCertificateService;
import org.zaproxy.addon.network.internal.cert.CertificateUtils;
import org.zaproxy.addon.network.internal.cert.GenerationException;
import org.zaproxy.addon.network.internal.cert.ServerCertificateGenerator;
import org.zaproxy.zap.extension.dynssl.DynSSLParam;
import org.zaproxy.zap.extension.dynssl.ExtensionDynSSL;

public class ExtensionNetwork extends ExtensionAdaptor {

    private static final Logger LOGGER = LogManager.getLogger(ExtensionNetwork.class);

    private static final String I18N_PREFIX = "network";

    Consumer<SslCertificateService> setSslCertificateService;
    boolean handleServerCerts;

    private ServerCertificatesOptions serverCertificatesOptions;

    private SslCertificateServiceImpl sslCertificateService;

    public ExtensionNetwork() {
        super(ExtensionNetwork.class.getSimpleName());

        setI18nPrefix(I18N_PREFIX);
    }

    boolean isHandleServerCerts() {
        return handleServerCerts;
    }

    @Override
    public void init() {
        handleServerCerts = ExtensionDynSSL.class.getAnnotation(Deprecated.class) != null;
        setSslCertificateService =
                new Consumer<SslCertificateService>() {

                    Method method;

                    @Override
                    public void accept(SslCertificateService sslCertificateService) {
                        try {
                            if (method == null) {
                                method =
                                        SSLConnector.class.getMethod(
                                                "setSslCertificateService",
                                                SslCertificateService.class);
                            }
                            method.invoke(SSLConnector.class, sslCertificateService);
                        } catch (Exception e) {
                            LOGGER.error(
                                    "An error occurred while setting the certificates service:", e);
                        }
                    }
                };
    }

    @Override
    public String getUIName() {
        return Constant.messages.getString("network.ext.name");
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString("network.ext.desc");
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        extensionHook.addApiImplementor(new NetworkApi(this));

        if (!handleServerCerts) {
            return;
        }

        sslCertificateService = new SslCertificateServiceImpl();

        serverCertificatesOptions = new ServerCertificatesOptions();
        extensionHook.addOptionsParamSet(serverCertificatesOptions);
    }

    ServerCertificatesOptions getServerCertificatesOptions() {
        return serverCertificatesOptions;
    }

    @Override
    public void start() {
        if (!handleServerCerts) {
            return;
        }

        if (loadRootCaCert()) {
            setSslCertificateService(sslCertificateService);
        }
    }

    private void setSslCertificateService(SslCertificateService sslCertificateService) {
        setSslCertificateService.accept(sslCertificateService);
    }

    class SslCertificateServiceImpl implements SslCertificateService {

        private ServerCertificateGenerator generator;

        @Override
        public void initializeRootCA(KeyStore keyStore) {
            generator = new ServerCertificateGenerator(keyStore, serverCertificatesOptions);
        }

        @Override
        public KeyStore createCertForHost(String hostname) {
            // Nothing to do, no longer used by core.
            return null;
        }

        @Override
        public KeyStore createCertForHost(CertData certData) throws IOException {
            if (generator == null) {
                throw new MissingRootCertificateException("The root CA certificate was not set.");
            }
            try {
                return generator.generate(certData);
            } catch (GenerationException e) {
                throw new IOException(e);
            }
        }
    }

    private boolean loadRootCaCert() {
        KeyStore rootCaKeyStore = getRootCaKeyStore();
        if (rootCaKeyStore == null) {
            return generateRootCaCert();
        }

        if (!applyRootCaCert()) {
            return false;
        }

        X509Certificate certificate = CertificateUtils.getCertificate(rootCaKeyStore);
        if (certificate == null || !certificate.getNotAfter().before(new Date())) {
            return true;
        }

        String warnMsg =
                Constant.messages.getString(
                        "network.warn.cert.expired",
                        certificate.getNotAfter().toString(),
                        new Date().toString());
        if (hasView()) {
            if (getView().showConfirmDialog(warnMsg) == JOptionPane.OK_OPTION) {
                if (!generateRootCaCert()) {
                    getView()
                            .showWarningDialog(
                                    Constant.messages.getString("network.warn.cert.failed"));
                }
                return true;
            }
        }
        LOGGER.warn(warnMsg);
        return true;
    }

    private boolean applyRootCaCert() {
        try {
            sslCertificateService.initializeRootCA(getRootCaKeyStore());
            return true;
        } catch (Exception e) {
            LOGGER.error("An error occurred while initializing the certificate service:", e);
        }
        return false;
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    @Override
    public void unload() {
        if (!handleServerCerts) {
            return;
        }

        setSslCertificateService(null);
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    @Override
    public boolean supportsDb(String type) {
        return true;
    }

    /**
     * Writes the Root CA certificate to the specified file in PEM format, suitable for importing
     * into browsers.
     *
     * @param path the path the Root CA certificate will be written to.
     * @throws IOException if an error occurred while writing the certificate.
     */
    public void writeRootCaCertAsPem(Path path) throws IOException {
        try {
            CertificateUtils.keyStoreToCertificatePem(getRootCaKeyStore(), path);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    KeyStore getRootCaKeyStore() {
        if (handleServerCerts) {
            return serverCertificatesOptions.getRootCaKeyStore();
        }

        DynSSLParam param = Model.getSingleton().getOptionsParam().getParamSet(DynSSLParam.class);
        if (param == null) {
            return null;
        }
        return param.getRootca();
    }

    boolean generateRootCaCert() {
        if (handleServerCerts) {
            try {
                LOGGER.info("Creating new root CA certificate.");
                KeyStore keyStore =
                        CertificateUtils.createRootCaKeyStore(
                                serverCertificatesOptions.getRootCaCertConfig());
                serverCertificatesOptions.setRootCaKeyStore(keyStore);
                LOGGER.info("New root CA certificate created.");
            } catch (Exception e) {
                LOGGER.error("Failed to create new root CA certificate:", e);
                return false;
            }

            return applyRootCaCert();
        }

        ExtensionDynSSL extDyn =
                Control.getSingleton().getExtensionLoader().getExtension(ExtensionDynSSL.class);
        if (extDyn != null) {
            try {
                extDyn.createNewRootCa();
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to create the new Root CA cert:", e);
            }
        }
        return false;
    }

    String importRootCaCert(Path pemFile) {
        if (handleServerCerts) {
            String pem;
            try {
                pem = new String(Files.readAllBytes(pemFile), StandardCharsets.US_ASCII);
            } catch (IOException e) {
                return Constant.messages.getString(
                        "network.importpem.failedreadfile", e.getLocalizedMessage());
            }

            byte[] certificate;
            try {
                certificate = CertificateUtils.extractCertificate(pem);
                if (certificate.length == 0) {
                    return Constant.messages.getString(
                            "network.importpem.nocertsection",
                            CertificateUtils.BEGIN_CERTIFICATE_TOKEN,
                            CertificateUtils.END_CERTIFICATE_TOKEN);
                }
            } catch (IllegalArgumentException e) {
                return Constant.messages.getString("network.importpem.certnobase64");
            }

            byte[] key;
            try {
                key = CertificateUtils.extractPrivateKey(pem);
                if (key.length == 0) {
                    return Constant.messages.getString(
                            "network.importpem.noprivkeysection",
                            CertificateUtils.BEGIN_PRIVATE_KEY_TOKEN,
                            CertificateUtils.END_PRIVATE_KEY_TOKEN);
                }
            } catch (IllegalArgumentException e) {
                return Constant.messages.getString("network.importpem.privkeynobase64");
            }

            try {
                KeyStore keyStore = CertificateUtils.pemToKeyStore(certificate, key);
                serverCertificatesOptions.setRootCaKeyStore(keyStore);
                applyRootCaCert();
                return null;
            } catch (Exception e) {
                return Constant.messages.getString(
                        "network.importpem.failedkeystore", e.getLocalizedMessage());
            }
        }

        ExtensionDynSSL extDyn =
                Control.getSingleton().getExtensionLoader().getExtension(ExtensionDynSSL.class);
        if (extDyn != null) {
            return extDyn.importRootCaCertificate(pemFile.toFile());
        }
        return "";
    }
}