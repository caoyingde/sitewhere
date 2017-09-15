package com.sitewhere.microservice.configuration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sitewhere.microservice.Microservice;
import com.sitewhere.microservice.spi.configuration.ConfigurationState;
import com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice;
import com.sitewhere.microservice.spi.configuration.IConfigurationListener;
import com.sitewhere.microservice.spi.configuration.IConfigurationMonitor;
import com.sitewhere.server.lifecycle.CompositeLifecycleStep;
import com.sitewhere.server.lifecycle.InitializeComponentLifecycleStep;
import com.sitewhere.server.lifecycle.StartComponentLifecycleStep;
import com.sitewhere.server.lifecycle.StopComponentLifecycleStep;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.server.lifecycle.ICompositeLifecycleStep;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;

/**
 * Base class for microservices that monitor the configuration folder for
 * updates.
 * 
 * @author Derek
 */
public abstract class ConfigurableMicroservice extends Microservice
	implements IConfigurableMicroservice, IConfigurationListener {

    /** Static logger instance */
    private static Logger LOGGER = LogManager.getLogger();

    /** Max wait time for configuration in seconds */
    private static final int MAX_CONFIGURATION_WAIT_SEC = 30;

    /** Configuration monitor */
    private IConfigurationMonitor configurationMonitor;

    /** Configuration state */
    private ConfigurationState configurationState = ConfigurationState.NotStarted;

    /** Indicates if configuration cache is ready to use */
    private boolean configurationCacheReady = false;

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationCacheInitialized()
     */
    @Override
    public void onConfigurationCacheInitialized() {
	LOGGER.info("Configuration cache initialized.");
	setConfigurationCacheReady(true);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationAdded(java.lang.String, byte[])
     */
    @Override
    public void onConfigurationAdded(String path, byte[] data) {
	if (isConfigurationCacheReady()) {
	    LOGGER.info("Configuration added for '" + path + "'.");
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationUpdated(java.lang.String, byte[])
     */
    @Override
    public void onConfigurationUpdated(String path, byte[] data) {
	if (isConfigurationCacheReady()) {
	    LOGGER.info("Configuration updated for '" + path + "'.");
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationDeleted(java.lang.String)
     */
    @Override
    public void onConfigurationDeleted(String path) {
	if (isConfigurationCacheReady()) {
	    LOGGER.info("Configuration deleted for '" + path + "'.");
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * getConfigurationDataFor(java.lang.String)
     */
    @Override
    public byte[] getConfigurationDataFor(String path) throws SiteWhereException {
	if (!isConfigurationCacheReady()) {
	    throw new SiteWhereException("Configuration cache not initialized.");
	}
	return getConfigurationMonitor().getConfigurationDataFor(path);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.server.lifecycle.LifecycleComponent#initialize(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void initialize(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.initialize(monitor);

	// Organizes steps for initializing microservice.
	ICompositeLifecycleStep initialize = new CompositeLifecycleStep("Initialize " + getName());

	// Create and initialize configuration monitor.
	createConfigurationMonitor();
	initialize.addStep(new InitializeComponentLifecycleStep(this, getConfigurationMonitor(),
		"Configuration Monitor", "Unable to initialize configuration monitor", true));

	// Start configuration monitor.
	initialize.addStep(new StartComponentLifecycleStep(this, getConfigurationMonitor(), "Configuration Monitor",
		"Unable to start configuration monitor", true));

	// Execute initialization steps.
	initialize.execute(monitor);
    }

    /**
     * Create configuration monitor for microservice.
     * 
     * @throws SiteWhereException
     */
    protected void createConfigurationMonitor() throws SiteWhereException {
	this.configurationMonitor = new ConfigurationMonitor(getZookeeperManager(), getInstanceConfigurationPath());
	getConfigurationMonitor().getListeners().add(this);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.server.lifecycle.LifecycleComponent#terminate(com.sitewhere
     * .spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void terminate(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.terminate(monitor);

	// Organizes steps for stopping microservice.
	ICompositeLifecycleStep stop = new CompositeLifecycleStep("Stop " + getName());

	// Stop configuration monitor.
	stop.addStep(new StopComponentLifecycleStep(this, getConfigurationMonitor(), "Configuration Monitor"));

	// Execute shutdown steps.
	stop.execute(monitor);

	// Terminate configuration monitor.
	getConfigurationMonitor().lifecycleTerminate(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * waitForConfigurationReady()
     */
    @Override
    public void waitForConfigurationReady() throws SiteWhereException {
	LOGGER.info("Waiting for configuration to be loaded...");
	long deadline = System.currentTimeMillis() + (1000 * MAX_CONFIGURATION_WAIT_SEC);
	while (true) {
	    if ((deadline - System.currentTimeMillis()) < 0) {
		throw new SiteWhereException("Microservice not configured within allowable timeframe.");
	    }
	    if (getConfigurationState() == ConfigurationState.Failed) {
		throw new SiteWhereException("Microservice configuration failed.");
	    }
	    if (getConfigurationState() == ConfigurationState.Succeeded) {
		LOGGER.info("Configuration loaded successfully.");
		return;
	    }
	    try {
		Thread.sleep(1000);
	    } catch (InterruptedException e) {
		return;
	    }
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.Microservice#getConfigurationMonitor()
     */
    @Override
    public IConfigurationMonitor getConfigurationMonitor() {
	return configurationMonitor;
    }

    public void setConfigurationMonitor(IConfigurationMonitor configurationMonitor) {
	this.configurationMonitor = configurationMonitor;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * getConfigurationState()
     */
    @Override
    public ConfigurationState getConfigurationState() {
	return configurationState;
    }

    public void setConfigurationState(ConfigurationState configurationState) {
	this.configurationState = configurationState;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * isConfigurationCacheReady()
     */
    @Override
    public boolean isConfigurationCacheReady() {
	return configurationCacheReady;
    }

    public void setConfigurationCacheReady(boolean configurationCacheReady) {
	this.configurationCacheReady = configurationCacheReady;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#getLogger()
     */
    @Override
    public Logger getLogger() {
	return LOGGER;
    }
}