package com.avdhoot.betterreadsdataloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import com.avdhoot.betterreadsdataloader.author.Author;
import com.avdhoot.betterreadsdataloader.author.AuthorRepository;
import com.avdhoot.betterreadsdataloader.book.Book;
import com.avdhoot.betterreadsdataloader.book.BookRepository;
import com.avdhoot.betterreadsdataloader.connection.DataStaxAstraProperties;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cassandra.CqlSessionBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(DataStaxAstraProperties.class)
public class BetterreadsDataLoaderApplication {

	@Autowired
	AuthorRepository authorRepository;

	@Autowired
	BookRepository bookRepository;

	@Value("${datadump.location.author}")
	private String authorDumpLocation;

	@Value("${datadump.location.works}")
	private String worksDumpLocation;

	public static void main(String[] args) {
		SpringApplication.run(BetterreadsDataLoaderApplication.class, args);
	}

	private void initAuthors() {
		Path path = Paths.get(authorDumpLocation);
		try (Stream<String> lines = Files.lines(path)) {
			lines.forEach(line -> {
				String jsonString = line.substring(line.indexOf("{"));
				try {
					JSONObject jsonObject = new JSONObject(jsonString);
					Author author = new Author();
					author.setName(jsonObject.optString("name"));
					author.setPersonalName(jsonObject.optString("personal_name"));
					author.setId(jsonObject.optString("key").replaceAll("/authors/", ""));

					System.out.println("Saving author " + author.getName() + "...");
					authorRepository.save(author);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void initWorks() {
		Path path = Paths.get(worksDumpLocation);
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
		try (Stream<String> lines = Files.lines(path)) {
			lines.forEach(line -> {
				String jsonString = line.substring(line.indexOf("{"));

				try {
					JSONObject jsonObject = new JSONObject(jsonString);
					Book book = new Book();
					book.setId(jsonObject.getString("key").replace("/works/", ""));
					JSONObject descriptionObject = jsonObject.optJSONObject("description");
					if (descriptionObject != null) {
						book.setDescription(descriptionObject.optString("value"));
					}
					book.setName(jsonObject.optString("title"));
					System.out.println(book.getName());
					JSONArray authorsArray = jsonObject.optJSONArray("authors");

					if (authorsArray != null) {
						List<String> authorIds = new ArrayList<>();
						for (int i = 0; i < authorsArray.length(); ++i) {
							JSONObject authorObj = authorsArray.getJSONObject(i);
							String authorId = authorObj.getJSONObject("author").getString("key").replace("/authors/",
									"");
							authorIds.add(authorId);
						}
						book.setAuthorId(authorIds);
						List<String> authorNames = authorIds.stream().map(id -> authorRepository.findById(id))
								.map(optionalAuthor -> {
									if (!optionalAuthor.isPresent())
										return "Unknown Author";
									return optionalAuthor.get().getName();
								}).collect(Collectors.toList());
						book.setAuthorNames(authorNames);
					}

					JSONObject publishedDateObj = jsonObject.optJSONObject("created");
					if (publishedDateObj != null) {
						String dateStr = publishedDateObj.getString("value");
						book.setPublishedDate(LocalDate.parse(dateStr,formatter));
					}
					JSONArray coversArray = jsonObject.optJSONArray("covers");
					if (coversArray != null) {
						List<String> coverIds = new ArrayList<>();
						for (int i = 0; i < coversArray.length(); ++i) {
							coverIds.add(coversArray.getString(i));
						}
						book.setCoverIds(coverIds);
					}
					System.out.println("Saving book " + book.getName() + "...");
					bookRepository.save(book);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			});
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@PostConstruct
	public void start() {
		//initAuthors();
		initWorks();
	}

	@Bean
	public CqlSessionBuilderCustomizer sessionBuilderCustomizer(DataStaxAstraProperties astraProperties) {
		Path bundle = astraProperties.getSecureConnectBundle().toPath();
		return builder -> builder.withCloudSecureConnectBundle(bundle);
	}

}
