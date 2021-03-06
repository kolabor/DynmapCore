package org.dynmap.hdmap;

import static org.dynmap.JSONUtils.s;

import java.io.IOException;
import java.util.List;

import org.dynmap.Color;
import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapCore;
import org.dynmap.Log;
import org.dynmap.MapManager;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.exporter.OBJExport;
import org.dynmap.utils.DynLongHashMap;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.dynmap.utils.BlockStep;
import org.json.simple.JSONObject;

public class TopoHDShader implements HDShader {
    private final String name;
    private final Color linecolor;  /* Color for topo lines */
    private final Color fillcolor[];  /* Color for nontopo surfaces */
    private final Color watercolor;
    private int[] hiddenids;
    
    private Color readColor(String id, ConfigurationNode cfg) {
        String lclr = cfg.getString(id, null);
        if((lclr != null) && (lclr.startsWith("#"))) {
            try {
                int c = Integer.parseInt(lclr.substring(1), 16);
                return new Color((c>>16)&0xFF, (c>>8)&0xFF, c&0xFF);
            } catch (NumberFormatException nfx) {
                Log.severe("Invalid color value: " + lclr + " for '" + id + "'");
            }
        }
        return null;
    }
    public TopoHDShader(DynmapCore core, ConfigurationNode configuration) {
        name = (String) configuration.get("name");
        
        fillcolor = new Color[256];   /* Color by Y */
        /* Load defined colors from parameters */
        for(int i = 0; i < 256; i++) {
            fillcolor[i] = readColor("color" + i,  configuration);
        }
        linecolor = readColor("linecolor",  configuration);
        watercolor = readColor("watercolor",  configuration);
        float wateralpha = configuration.getFloat("wateralpha", 1.0F);
        if (wateralpha < 1.0) {
            watercolor.setAlpha((int)(255 * wateralpha));
        }
        /* Now, interpolate missing colors */
        if(fillcolor[0] == null) {
            fillcolor[0] = new Color(0, 0, 0);
        }
        if(fillcolor[255] == null) {
            fillcolor[255] = new Color(255, 255, 255);
        }
        int starty = 0;
        for(int i = 1; i < 256; i++) {
            if(fillcolor[i] != null) {  /* Found color? */
                int delta = i - starty;
                Color c0 = fillcolor[starty];
                Color c1 = fillcolor[i];
                /* Interpolate missing colors since last one */
                for(int j = 1; j < delta; j++) {
                    fillcolor[starty + j] = new Color((c0.getRed()*(delta-j) + c1.getRed()*j)/delta, (c0.getGreen()*(delta-j) + c1.getGreen()*j)/delta, (c0.getBlue()*(delta-j) + c1.getBlue()*j)/delta);
                }
                starty = i;  /* New start color */
            }
        }
        hiddenids = new int[2048];
        hiddenids[0] = 0x1; /* Air is hidden always */
        List<Object> hidden = configuration.getList("hiddenids");
        if(hidden != null) {
            for(Object o : hidden) {
                if(o instanceof Integer) {
                    int v = ((Integer)o);
                    if((v > 0) && (v < 65535)) {
                        hiddenids[v >> 5] |= (1 << (v & 0x1F));
                    }
                }
            }
        }
    }
    
    @Override
    public boolean isBiomeDataNeeded() { 
        return false; 
    }
    
    @Override
    public boolean isRawBiomeDataNeeded() { 
        return false; 
    }
    
    @Override
    public boolean isHightestBlockYDataNeeded() {
        return false;
    }

    @Override
    public boolean isBlockTypeDataNeeded() {
        return true;
    }

    @Override
    public boolean isSkyLightLevelNeeded() {
        return false;
    }

    @Override
    public boolean isEmittedLightLevelNeeded() {
        return false;
    }

    @Override
    public String getName() {
        return name;
    }
    
    private class OurShaderState implements HDShaderState {
        private Color color[];
        private Color tmpcolor[];
        private Color c;
        protected MapIterator mapiter;
        protected HDMap map;
        private HDLighting lighting;
        private int scale;
        private int heightshift;    /* Divide to keep in 0-127 range of colors */
        private boolean inWater;
        final int[] lightingTable;
        
        private OurShaderState(MapIterator mapiter, HDMap map, MapChunkCache cache, int scale) {
            this.mapiter = mapiter;
            this.map = map;
            this.lighting = map.getLighting();
            if(lighting.isNightAndDayEnabled()) {
                color = new Color[] { new Color(), new Color() };
                tmpcolor = new Color[] { new Color(), new Color() };
            }
            else {
                color = new Color[] { new Color() };
                tmpcolor = new Color[] { new Color() };
            }
            this.scale = scale;
            c = new Color();
            /* Compute divider for Y - to map to existing color range */
            int wh = mapiter.getWorldHeight();
            heightshift = 0;
            while(wh > 256) {
                heightshift++;
                wh >>= 1;
            }
            if (MapManager.mapman.useBrightnessTable()) {
                lightingTable = cache.getWorld().getBrightnessTable();
            }
            else {
                lightingTable = null;
            }
            inWater = false;
        }
        /**
         * Get our shader
         */
        public HDShader getShader() {
            return TopoHDShader.this;
        }

        /**
         * Get our map
         */
        public HDMap getMap() {
            return map;
        }
        
        /**
         * Get our lighting
         */
        public HDLighting getLighting() {
            return lighting;
        }
        
        /**
         * Reset renderer state for new ray
         */
        public void reset(HDPerspectiveState ps) {
            for(int i = 0; i < color.length; i++)
                color[i].setTransparent();
            inWater = false;
        }
        
        private final boolean isHidden(int id) {
            if((id == 0) || ((hiddenids[id >> 5] & (1 << (id & 0x1F))) != 0)) {
                return true;
            }
            return false;
        }
        /**
         * Process next ray step - called for each block on route
         * @return true if ray is done, false if ray needs to continue
         */
        public boolean processBlock(HDPerspectiveState ps) {
            int blocktype = ps.getBlockTypeID();
            
            if(isHidden(blocktype)) {
                return false;
            }
            /* See if we're close to an edge */
            int[] xyz = ps.getSubblockCoord();
            
            /* See which face we're on (only do lines on top face) */
            switch(ps.getLastBlockStep()) {
            case Y_MINUS:
            case Y_PLUS:
                if((linecolor != null) &&
                        (((xyz[0] == 0) && (isHidden(mapiter.getBlockTypeIDAt(BlockStep.X_MINUS)))) ||
                        ((xyz[0] == (scale-1)) && (isHidden(mapiter.getBlockTypeIDAt(BlockStep.X_PLUS)))) ||
                        ((xyz[2] == 0) && (isHidden(mapiter.getBlockTypeIDAt(BlockStep.Z_MINUS)))) ||
                        ((xyz[2] == (scale-1)) && (isHidden(mapiter.getBlockTypeIDAt(BlockStep.Z_PLUS)))))) {
                    c.setColor(linecolor);
                    inWater = false;
                }
                else if((watercolor != null) && ((blocktype == 8) || (blocktype == 9))) {
                    if (!inWater) {
                        c.setColor(watercolor);
                        inWater = true;
                    }
                    else {
                        return false;
                    }
                }
                else {
                    c.setColor(fillcolor[mapiter.getY() >> heightshift]);
                    inWater = false;
                }
                break;
            default:
                if((linecolor != null) && (xyz[1] == (scale-1))) {
                    c.setColor(linecolor);
                    inWater = false;
                }
                else if((watercolor != null) && ((blocktype == 8) || (blocktype == 9))) {
                    if (!inWater) {
                        c.setColor(watercolor);
                        inWater = true;
                    }
                    else {
                        return false;
                    }
                }
                else {
                    c.setColor(fillcolor[mapiter.getY() >> heightshift]);
                    inWater = false;
                }
                break;
            }
            /* Handle light level, if needed */
            lighting.applyLighting(ps, this, c, tmpcolor);
            
            /* If no previous color contribution, use new color */
            if(color[0].isTransparent()) {
                for(int i = 0; i < color.length; i++)
                    color[i].setColor(tmpcolor[i]);
                return (color[0].getAlpha() == 255);
            }
            /* Else, blend and generate new alpha */
            else {
                int alpha = color[0].getAlpha();
                int alpha2 = tmpcolor[0].getAlpha() * (255-alpha) / 255;
                int talpha = alpha + alpha2;
                if(talpha > 0)
                    for(int i = 0; i < color.length; i++)
                        color[i].setRGBA((tmpcolor[i].getRed()*alpha2 + color[i].getRed()*alpha) / talpha,
                              (tmpcolor[i].getGreen()*alpha2 + color[i].getGreen()*alpha) / talpha,
                              (tmpcolor[i].getBlue()*alpha2 + color[i].getBlue()*alpha) / talpha, talpha);
                else
                    for(int i = 0; i < color.length; i++)
                        color[i].setTransparent();
                    
                return (talpha >= 254);   /* If only one short, no meaningful contribution left */
            }
        }        
        /**
         * Ray ended - used to report that ray has exited map (called if renderer has not reported complete)
         */
        public void rayFinished(HDPerspectiveState ps) {
        }
        /**
         * Get result color - get resulting color for ray
         * @param c - object to store color value in
         * @param index - index of color to request (renderer specific - 0=default, 1=day for night/day renderer
         */
        public void getRayColor(Color c, int index) {
            c.setColor(color[index]);
        }
        /**
         * Clean up state object - called after last ray completed
         */
        public void cleanup() {
        }
        @Override
        public DynLongHashMap getCTMTextureCache() {
            return null;
        }
        @Override
        public int[] getLightingTable() {
            return lightingTable;
        }
    }

    /**
     *  Get renderer state object for use rendering a tile
     * @param map - map being rendered
     * @param cache - chunk cache containing data for tile to be rendered
     * @param mapiter - iterator used when traversing rays in tile
     * @param scale - scale of perspecitve
     * @return state object to use for all rays in tile
     */
    @Override
    public HDShaderState getStateInstance(HDMap map, MapChunkCache cache, MapIterator mapiter, int scale) {
        return new OurShaderState(mapiter, map, cache, scale);
    }
    
    /* Add shader's contributions to JSON for map object */
    public void addClientConfiguration(JSONObject mapObject) {
        s(mapObject, "shader", name);
    }
    @Override
    public void exportAsMaterialLibrary(DynmapCommandSender sender, OBJExport out) throws IOException {
        throw new IOException("Export unsupported");
    }
    private static final String[] nulllist = new String[0];
    @Override
    public String[] getCurrentBlockMaterials(int blkid, int blkdata, int renderdata, MapIterator mapiter, int[] txtidx, BlockStep[] steps) {
        return nulllist;
    }
}
