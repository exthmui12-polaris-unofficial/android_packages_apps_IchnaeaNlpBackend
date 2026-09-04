/*
 * SPDX-FileCopyrightText: 2015 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.nlp.backend.ichnaea;

import android.location.Location;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.microg.nlp.api.CellBackendHelper;
import org.microg.nlp.api.LocationHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/*
 * This class implements the runnable portion of a thread which
 * accepts requests to process a location request and returns results
 * via a callback
 */
public class IchnaeaRequester implements Runnable {

    private static final String TAG = "IchnaeaBackendService";
    private static final String SERVICE_URL = "https://api.beacondb.net/v1/geolocate";
    /* The test network's DNS sinkholes this hostname to 198.18.0.4. Keep the
       TLS hostname/SNI while connecting to the currently published address. */
    private static final String SERVICE_IP = "49.13.72.183";
    private static final String PROVIDER = "ichnaea";

    private final LocationCallback callback;
    private final CellDatabase cellDatabase;
    private final CellBackendHelper.Cell singleCell;
    private final String cellRequest;
    private final String wifiRequest;

    public IchnaeaRequester(LocationCallback backendService, CellDatabase cellDatabase, CellBackendHelper.Cell singleCell, String cellRequest, String wifiRequest) {
        this.callback = backendService;
        this.cellDatabase = cellDatabase;
        this.singleCell = singleCell;
        this.cellRequest = cellRequest;
        this.wifiRequest = wifiRequest;
    }


    public void run() {
        Location cellLocation = null;
        if (singleCell != null) {
            cellLocation = cellDatabase.getLocation(singleCell);
        }
        if (cellLocation == null && cellRequest != null) {
            cellLocation = request(cellRequest);
            if (cellLocation == null) {
                this.callback.extendBackoff();
                this.callback.resultCallback(null);
                return;
            }
            if (singleCell != null) {
                cellDatabase.putLocation(singleCell, cellLocation);
            }
        }
        Location wifiLocation = null;
        if (wifiRequest != null) {
            wifiLocation = request(wifiRequest);
            if (wifiLocation == null) {
                this.callback.extendBackoff();
            }
        }
        if (cellLocation == null || cellLocation.getAccuracy() == 0.0) {
            this.callback.resultCallback(wifiLocation);
        } else if (wifiLocation == null || wifiLocation.getAccuracy() == 0.0) {
            this.callback.resultCallback(cellLocation);
        } else {
            if (cellLocation.distanceTo(wifiLocation) > cellLocation.getAccuracy() * 2) {
                // Wifi Location is too far off
                this.callback.resultCallback(cellLocation);
            } else {
                this.callback.resultCallback(wifiLocation);
            }
        }
    }

    private Location request(String request) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SERVICE_URL).openConnection();
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(new FixedAddressSSLSocketFactory(
                        (SSLSocketFactory) SSLSocketFactory.getDefault(), SERVICE_IP));
            }
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            int respCode = conn.getResponseCode();
            if ((respCode >= 400) && (respCode <= 599)) {
                Log.w(TAG, "response code 400-600 -> backoff");
                return null;
            }
            String r = new String(readStreamToEnd(conn.getInputStream()));
            JSONObject responseJson = new JSONObject(r);
            double lat = responseJson.getJSONObject("location").getDouble("lat");
            double lon = responseJson.getJSONObject("location").getDouble("lng");
            double acc = responseJson.getDouble("accuracy");
            return LocationHelper.create(PROVIDER, lat, lon, (float) acc);
        } catch (Exception e) {
            if (conn != null) {
                InputStream is = conn.getErrorStream();
                if (is != null) {
                    try {
                        String error = new String(readStreamToEnd(is));
                        Log.w(TAG, "Error: " + error);
                    } catch (Exception ignored) {
                    }
                }
            }
            Log.w(TAG, "Error", e);
            return null;
        }
    }

    private static byte[] readStreamToEnd(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buff = new byte[1024];
            while (true) {
                int nb = is.read(buff);
                if (nb < 0) {
                    break;
                }
                bos.write(buff, 0, nb);
            }
            is.close();
        }
        return bos.toByteArray();
    }

    private static final class FixedAddressSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String address;

        FixedAddressSSLSocketFactory(SSLSocketFactory delegate, String address) {
            this.delegate = delegate;
            this.address = address;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(address, port), 15000);
            return delegate.createSocket(raw, host, port, true);
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            if (autoClose) socket.close();
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(address, port), 15000);
            return delegate.createSocket(raw, host, port, true);
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localAddress, int localPort) throws IOException {
            return createSocket(host, port);
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return createSocket(host.getHostName(), port);
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws IOException {
            return createSocket(address.getHostName(), port);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }
}
