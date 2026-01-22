/*
 * Copyright (C) 2016 the original author or authors.
 * See the NOTICE.md file distributed with this work for additional
 * information regarding copyright ownership.
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

package io.github.atetzner.webdav.server;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.resource.CollectionResource;
import io.milton.resource.FolderResource;
import io.milton.resource.Resource;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.mina.core.RuntimeIoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * A {@link FolderResource milton FolderResource} to serve the contents of a single folder.
 */
public class MiltonFolderResource implements FolderResource {

	private static final Logger LOGGER = LoggerFactory.getLogger(MiltonFolderResource.class);

	private final Path file;
	private final MiltonWebDAVResourceFactory resourceFactory;

	public MiltonFolderResource(Path file, MiltonWebDAVResourceFactory resourceFactory) {
		this.file = file;
		this.resourceFactory = resourceFactory;
	}

	@Override
	public Resource child(String childName) throws NotAuthorizedException, BadRequestException {
		LOGGER.debug("Getting child {} in {}", childName, this.file);

		Path child = file.resolve(childName);
		if (!Files.exists(child)) {
			return null;
		} else if (Files.isDirectory(child)) {
			return new MiltonFolderResource(child, resourceFactory);
		} else {
			return new MiltonFileResource(child, resourceFactory);
		}
	}

	@Override
	public List<? extends Resource> getChildren() throws NotAuthorizedException, BadRequestException {
		LOGGER.debug("Getting children in {}", this.file);

		List<Resource> children = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.file)) {
			for (Path path : stream) {
				if (Files.isDirectory(path)) {
					children.add(new MiltonFolderResource(path, resourceFactory));
				} else {
					children.add(new MiltonFileResource(path, resourceFactory));
				}
			}
		} catch (IOException e) {
			LOGGER.error("Error listing directory {}", this.file, e);
			throw new RuntimeIoException(e);
		}
		return children;
	}

	@Override
	public void copyTo(CollectionResource toCollection, String name) throws NotAuthorizedException, BadRequestException,
			ConflictException {
		LOGGER.debug("Copying folder {} to {}/{}", this.file, toCollection.getName(), name);

		Path destinationRootFolder = resourceFactory.getRootFolder().resolve(toCollection.getName());
		Path destinationFolder = destinationRootFolder.resolve(name);

		try {
			FileUtils.copyDirectory(this.file.toFile(), destinationFolder.toFile());
		} catch (IOException e) {
			LOGGER.error("Error copying folder {}", this.file, e);
			throw new RuntimeIoException(e);
		}
	}

	@Override
	public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
		LOGGER.debug("Deleting {}", this.file);

		try {
			Files.delete(this.file);
		} catch (IOException e) {
			LOGGER.error("Error deleting folder {}", this.file, e);
			throw new RuntimeIoException(e);
		}
	}

	@Override
	public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws
			IOException, NotAuthorizedException, BadRequestException, NotFoundException {
		LOGGER.debug("Sending content for folder {} and contenttype {}", this.file, contentType);
		String relativePath = getRootRelativePath();

		PrintWriter w = new PrintWriter(out);
		w.println("<html><head><title>Folder listing for " + relativePath + "</title></head>");
		w.println("<body>");
		w.println("<h1>Folder listing for " + relativePath + "</h1>");
		w.println("<ul>");
		this.file.forEach(f -> {
			String childRelativePath = getRootRelativePath(f);
			w.println("<li><a href=\"" + childRelativePath + "\">" + f.getFileName() + "</a></li>");
		});
		w.println("</ul></body></html>");
		w.flush();
		w.close();
	}

	@Override
	public Long getMaxAgeSeconds(Auth auth) {
		return null;
	}

	@Override
	public String getContentType(String accepts) {
		return null;
	}

	@Override
	public Long getContentLength() {
		return null;
	}

	@Override
	public CollectionResource createCollection(String newName) throws NotAuthorizedException, ConflictException,
			BadRequestException {
		Path subfolder = this.file.resolve(newName);
		try {
			Files.createDirectory(subfolder);
			LOGGER.debug("Created folder {}", subfolder);
			return new MiltonFolderResource(subfolder, resourceFactory);
		} catch (IOException e) {
			LOGGER.warn("Could not create subfolder {}", subfolder, e);
			return null;
		}
	}

	@Override
	public void moveTo(CollectionResource rDest, String name) throws ConflictException, NotAuthorizedException,
			BadRequestException {
		LOGGER.debug("Moving {} to {}/{}", this.file, rDest.getName(), name);

		Path newRootDir = resourceFactory.getRootFolder().resolve(rDest.getName());
		Path newDir = newRootDir.resolve(name);

		try {
			FileUtils.moveDirectory(this.file.toFile(), newDir.toFile());
		} catch (IOException e) {
			LOGGER.error("Error moving {} to {}", this.file, newDir);
			throw new RuntimeIoException(e);
		}
	}

	@Override
	public Date getCreateDate() {
		return null;
	}

	@Override
	public Resource createNew(String newName, InputStream inputStream, Long length, String contentType) throws
			IOException, ConflictException, NotAuthorizedException, BadRequestException {
		Path newFile = this.file.resolve(newName);
		try (OutputStream out = Files.newOutputStream(newFile)) {
			IOUtils.copy(inputStream, out);

			return new MiltonFileResource(newFile, resourceFactory);
		} catch (Exception e) {
			LOGGER.error("Error creating file {}", newFile, e);
			throw new RuntimeIoException(e);
		}
	}

	@Override
	public String getUniqueId() {
		return file.toAbsolutePath().toString();
	}

	@Override
	public String getName() {
		return file.toString();
	}

	@Override
	public Object authenticate(String user, String password) {
		LOGGER.debug("Authenticating user {} for resource {}", user, this.file);

		if (resourceFactory.getSecurityManager() != null) {
			return resourceFactory.getSecurityManager().authenticate(user, password);
		}
		return user;
	}

	@Override
	public boolean authorise(Request request, Request.Method method, Auth auth) {
		if (auth != null) {
			LOGGER.debug("Authorizing user {} for resource {}", auth.getUser(), this.file);
		}

		return resourceFactory.getSecurityManager() == null || resourceFactory.getSecurityManager()
				.authorise(request, method, auth, this);
	}

	@Override
	public String getRealm() {
		return file.toAbsolutePath().toString();
	}

	@Override
	public Date getModifiedDate() {
		return new Date(file.toFile().lastModified());
	}

	@Override
	public String checkRedirect(Request request) throws NotAuthorizedException, BadRequestException {
		// no redirect
		return null;
	}

	private String getRootRelativePath() {
		return getRootRelativePath(this.file);
	}

	private String getRootRelativePath(Path file) {
		return "/" + resourceFactory.getRootFolder().relativize(file);
	}
}
