package smsgateway.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import smsgateway.smpp.DynamicVendorConfigWatcher;
import smsgateway.smpp.VendorConf;

class SmppVendorConfigResourceTest {

    @Mock private DynamicVendorConfigWatcher mockWatcher;

    @Mock private UriInfo mockUriInfo; // To mock URI building for POST

    @InjectMocks private SmppVendorConfigResource resource;

    private VendorConf vendor1;
    private VendorConf vendor2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        vendor1 =
                new VendorConf(
                        "v1", true, "host1", 2775, "sys1", "pass1", 30, 60, 10.0, "SMPP", "", "");
        vendor2 =
                new VendorConf(
                        "v2", false, "host2", 2776, "sys2", "pass2", 30, 60, 10.0, "SMPP", "", "");

        // Mocking for UriInfo in POST
        UriBuilder mockUriBuilder = mock(UriBuilder.class);
        when(mockUriInfo.getAbsolutePathBuilder()).thenReturn(mockUriBuilder);
        when(mockUriBuilder.path(anyString())).thenReturn(mockUriBuilder);
        when(mockUriBuilder.build())
                .thenReturn(URI.create("http://localhost/api/admin/vendors/v1"));
    }

    @Test
    void getAllVendors_Success() {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of(vendor1, vendor2));
        Response response = resource.getAllVendors();
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Set<VendorConf> vendors = (Set<VendorConf>) response.getEntity();
        assertEquals(2, vendors.size());
    }

    @Test
    void getAllVendors_Empty() {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of());
        Response response = resource.getAllVendors();
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Set<VendorConf> vendors = (Set<VendorConf>) response.getEntity();
        assertTrue(vendors.isEmpty());
    }

    @Test
    void getVendorById_Found() {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of(vendor1));
        Response response = resource.getVendorById("v1");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(vendor1, response.getEntity());
    }

    @Test
    void getVendorById_NotFound() {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of());
        Response response = resource.getVendorById("nonExistent");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("not found"));
    }

    @Test
    void getVendorById_EmptyId() {
        Response response = resource.getVendorById("");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID cannot be empty"));
    }

    @Test
    void getVendorById_NullId() {
        Response response = resource.getVendorById(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID cannot be empty"));
    }

    @Test
    void createVendor_Success() throws Exception {
        doNothing().when(mockWatcher).forceReloadConfig();

        Response response = resource.createVendor(vendor1, mockUriInfo);
        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        assertEquals(vendor1, response.getEntity());
        assertNotNull(response.getLocation());
        verify(mockWatcher).persistCurrentVendorsConf();
    }

    @Test
    void createVendor_Conflict() throws Exception {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of(vendor1));
        Response response = resource.createVendor(vendor1, mockUriInfo);
        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        verify(mockWatcher, never()).persistCurrentVendorsConf();
    }

    @Test
    void createVendor_NullBody() {
        Response response = resource.createVendor(null, mockUriInfo);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("cannot be null or empty"));
    }

    @Test
    void createVendor_NullIdInBody() {
        VendorConf vendorWithNullId =
                new VendorConf(null, true, "h", 1, "s", "p", 30, 60, 10.0, "SMPP", "", "");
        Response response = resource.createVendor(vendorWithNullId, mockUriInfo);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID cannot be null or empty"));
    }

    @Test
    void createVendor_ReloadThrowsException() throws Exception {
        doThrow(new RuntimeException("Test reload error")).when(mockWatcher).forceReloadConfig();
        Response response = resource.createVendor(vendor1, mockUriInfo);
        // Still CREATED because the resource was created, error is logged
        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        assertEquals(vendor1, response.getEntity());
        verify(mockWatcher).persistCurrentVendorsConf(); // Ensure it was called
    }

    @Test
    void updateVendor_Success() throws Exception {
        doNothing().when(mockWatcher).forceReloadConfig();

        Response response = resource.updateVendor("v1", vendor1);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(vendor1, response.getEntity());
        verify(mockWatcher).persistCurrentVendorsConf();
        verify(mockWatcher).removeVendor(vendor1);
        verify(mockWatcher).addVendor(vendor1);
    }

    @Test
    void updateVendor_NotFound() throws Exception {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of());
        Response response = resource.updateVendor("nonExistent", vendor1);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verify(mockWatcher, never()).persistCurrentVendorsConf();
    }

    @Test
    void updateVendor_NullId() {
        Response response = resource.updateVendor(null, vendor1);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID or vendor data cannot be null"));
    }

    @Test
    void updateVendor_NullBody() {
        Response response = resource.updateVendor("v1", null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID or vendor data cannot be null"));
    }

    @Test
    void updateVendor_IdMismatchInBody() {
        // Path ID is "v1", body ID is "v2"
        VendorConf vendorWithDifferentId =
                new VendorConf("v2", true, "h", 1, "s", "p", 30, 60, 10.0, "SMPP", "", "");
        Response response = resource.updateVendor("v1", vendorWithDifferentId);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("does not match ID in body"));
    }

    @Test
    void deleteVendor_Success() throws Exception {
        var test = new VendorConf();
        test.setId("v1");
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of(test));
        doNothing().when(mockWatcher).forceReloadConfig();

        Response response = resource.deleteVendor("v1");
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        verify(mockWatcher).persistCurrentVendorsConf();
    }

    @Test
    void deleteVendor_NotFound() throws Exception {
        when(mockWatcher.getCurrentVendorsConfs()).thenReturn(Set.of());
        Response response = resource.deleteVendor("nonExistent");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(mockWatcher, never()).persistCurrentVendorsConf();
    }

    @Test
    void deleteVendor_NullId() {
        Response response = resource.deleteVendor(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID cannot be empty"));
    }

    @Test
    void deleteVendor_EmptyId() {
        Response response = resource.deleteVendor("");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ID cannot be empty"));
    }
}
