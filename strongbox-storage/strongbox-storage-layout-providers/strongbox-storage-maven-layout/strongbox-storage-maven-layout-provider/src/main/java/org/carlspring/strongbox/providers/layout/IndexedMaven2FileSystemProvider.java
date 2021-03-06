package org.carlspring.strongbox.providers.layout;

import org.carlspring.strongbox.artifact.coordinates.MavenArtifactCoordinates;
import org.carlspring.strongbox.io.LayoutOutputStream;
import org.carlspring.strongbox.providers.io.LayoutFileSystem;
import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.io.TempRepositoryPath;
import org.carlspring.strongbox.repository.IndexedMavenRepositoryFeatures;
import org.carlspring.strongbox.services.ArtifactIndexesService;
import org.carlspring.strongbox.storage.indexing.IndexTypeEnum;
import org.carlspring.strongbox.storage.indexing.RepositoryIndexManager;
import org.carlspring.strongbox.storage.indexing.RepositoryIndexer;
import org.carlspring.strongbox.storage.repository.Repository;

import javax.inject.Inject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

import org.apache.commons.io.output.ProxyOutputStream;
import org.apache.maven.index.ArtifactInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author sbespalov
 *
 */
public class IndexedMaven2FileSystemProvider extends Maven2FileSystemProvider
{

    private static final Logger logger = LoggerFactory.getLogger(IndexedMaven2FileSystemProvider.class);

    @Inject
    private RepositoryIndexManager repositoryIndexManager;

    @Inject
    private ArtifactIndexesService artifactIndexesService;

    @Inject
    private IndexedMavenRepositoryFeatures mavenRepositoryFeatures;

    public IndexedMaven2FileSystemProvider(FileSystemProvider storageFileSystemProvider)
    {
        super(storageFileSystemProvider);
    }

    @Override
    public void delete(Path path,
                       boolean force)
        throws IOException
    {
        super.delete(path, force);

        try
        {
            deleteFromIndex((RepositoryPath) path);
        }
        catch (NoSuchFileException e)
        {
            String fileName = e.getMessage();
            RepositoryPath indexRoot = ((LayoutFileSystem)path.getFileSystem()).getRootDirectory().resolve(LayoutFileSystem.INDEX);
            // We should ignore this error for index files because there is no index files in group repositories.
            if (fileName == null || !fileName.startsWith(indexRoot.toString()))
            {
                throw e;
            }
        }
    }

    @Override
    public void undelete(RepositoryPath repositoryPath)
        throws IOException
    {
        super.undelete(repositoryPath);

        artifactIndexesService.rebuildIndex(repositoryPath);
    }

    public void deleteFromIndex(RepositoryPath path)
        throws IOException
    {
        if (Files.isDirectory(path))
        {
            return;
        }

        Repository repository = path.getRepository();
        if (!mavenRepositoryFeatures.isIndexingEnabled(repository))
        {
            return;
        }

        final RepositoryIndexer indexer = getRepositoryIndexer(path);
        if (indexer == null)
        {
            return;
        }
        if (!RepositoryFiles.isArtifact(path))
        {
            return;
        }
        
        MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) RepositoryFiles.readCoordinates(path);
        indexer.delete(Collections.singletonList(new ArtifactInfo(repository.getId(),
                coordinates.getGroupId(),
                coordinates.getArtifactId(),
                coordinates.getVersion(),
                coordinates.getClassifier(),
                coordinates.getExtension())));
    }

    public void closeIndex(RepositoryPath path)
        throws IOException
    {
        final RepositoryIndexer indexer = getRepositoryIndexer(path);
        if (indexer != null)
        {
            logger.debug("Closing indexer of path " + path + "...");

            indexer.close();
        }
    }

    private RepositoryIndexer getRepositoryIndexer(RepositoryPath path)
    {
        Repository repository = path.getFileSystem().getRepository();

        if (!mavenRepositoryFeatures.isIndexingEnabled(repository))
        {
            return null;
        }

        return repositoryIndexManager.getRepositoryIndexer(repository.getStorage().getId() + ":" +
                repository.getId() + ":" +
                IndexTypeEnum.LOCAL.getType());
    }

    @Override
    public RepositoryPath moveFromTemporaryDirectory(TempRepositoryPath tempPath)
        throws IOException
    {
        RepositoryPath result = super.moveFromTemporaryDirectory(tempPath);

        artifactIndexesService.addArtifactToIndex(result);

        return result;
    }

    @Override
    protected LayoutOutputStream decorateStream(RepositoryPath path,
                                                OutputStream os)
        throws NoSuchAlgorithmException,
        IOException
    {

        return super.decorateStream(path, new IndexedOutputStream(path, os));
    }

    private class IndexedOutputStream extends ProxyOutputStream
    {

        private RepositoryPath repositoryPath;
        
        public IndexedOutputStream(RepositoryPath repositoryPath, OutputStream out)
        {
            super(out);
            this.repositoryPath = repositoryPath;
        }

        @Override
        public void close()
            throws IOException
        {
            super.close();
            
            artifactIndexesService.addArtifactToIndex(repositoryPath);
        }

    }

}
